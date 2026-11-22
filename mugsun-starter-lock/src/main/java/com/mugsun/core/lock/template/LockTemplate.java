package com.mugsun.core.lock.template;

import com.mugsun.core.lock.client.LockClient;
import com.mugsun.core.lock.exception.LockFailedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * 加锁的入口。三种用法：
 *
 * <pre>{@code
 * // 1. 定时任务：多个实例里只让一个执行，别的实例直接跳过
 * lockTemplate.tryRun("job:log-clean", this::clean);
 *
 * // 2. 业务操作：最多等 3 秒，等不到就抛 LockFailedException（前端看到「操作正在进行中，请稍后再试」）
 * lockTemplate.run("order:pay:" + orderId, Duration.ofSeconds(3), () -> pay(orderId));
 * String no = lockTemplate.execute("serial:" + code, Duration.ofSeconds(3), () -> next(code));
 *
 * // 3. 自己拿句柄
 * try (LockHandle held = lockTemplate.tryLock("report:" + id).orElse(null)) {
 *     if (held == null) {
 *         return;
 *     }
 *     build(id);
 * }
 * }</pre>
 *
 * <p><b>租期</b>：不传租期时，锁一直占到释放为止——按默认租期（30 秒）上锁，每过三分之一租期自动续一次，
 * 所以执行多久都不会中途丢锁；进程崩溃以后没人续期，锁在一个租期内自己放开。传了租期就是固定租期：
 * 到时间一定放开，不续期，适合「最多只许占这么久」的场合。
 *
 * <p><b>重入</b>：同一个线程已经拿着某把锁，再抢同一把会直接拿到，不会自己把自己卡住；
 * 租期沿用最外层那一次的，最外层关闭时才真正释放。
 *
 * <p><b>边界</b>：锁挡的是「正常情况下的同时执行」。固定租期到期、Redis 数据丢失、进程长时间停顿时，
 * 仍然可能有第二个进来；不能接受的地方，数据库里要有唯一约束或版本号兜底。
 */
public class LockTemplate implements DisposableBean {

	/** 抢不到就立刻返回，不等 */
	public static final Duration NO_WAIT = Duration.ZERO;

	/** 锁在 Redis 里的统一前缀 */
	public static final String DEFAULT_KEY_PREFIX = "lock:";

	/** 不传租期时的租期：持有期间自动续，进程崩溃后最多这么久放开 */
	public static final Duration DEFAULT_LEASE_TIME = Duration.ofSeconds(30);

	/** 等锁时隔多久再抢一次 */
	public static final Duration DEFAULT_RETRY_INTERVAL = Duration.ofMillis(100);

	/** 没抢到锁时给前端的提示语 */
	public static final String DEFAULT_FAIL_MESSAGE = "操作正在进行中，请稍后再试";

	private static final Logger log = LoggerFactory.getLogger(LockTemplate.class);

	/** 再长的等待和租期，在本地算时间时也按这个上限算，避免换算成纳秒时溢出 */
	private static final long MAX_NANOS = TimeUnit.DAYS.toNanos(3650);

	private final LockClient client;

	private final String keyPrefix;

	private final Duration leaseTime;

	private final long retryNanos;

	/** 每个模板一个随机编号，加上序号就是持有者令牌：不同进程、同一进程的不同次抢锁都不会重复 */
	private final String instanceId = UUID.randomUUID().toString().replace("-", "");

	private final AtomicLong sequence = new AtomicLong();

	/** 本进程当前拿着的锁。锁是互斥的，所以每个锁名同一时刻最多一条有效记录 */
	private final ConcurrentHashMap<String, Held> holds = new ConcurrentHashMap<>();

	private final Object renewerMonitor = new Object();

	private volatile ScheduledThreadPoolExecutor renewer;

	private volatile boolean destroyed;

	public LockTemplate(LockClient client) {
		this(client, DEFAULT_KEY_PREFIX, DEFAULT_LEASE_TIME, DEFAULT_RETRY_INTERVAL);
	}

	/**
	 * @param keyPrefix 锁名前面统一加的前缀，可以是空串
	 * @param leaseTime 不传租期时的租期，必须大于 0
	 * @param retryInterval 等锁时隔多久再抢一次，必须大于 0
	 */
	public LockTemplate(LockClient client, String keyPrefix, Duration leaseTime, Duration retryInterval) {
		this.client = Objects.requireNonNull(client, "client");
		this.keyPrefix = keyPrefix == null ? "" : keyPrefix;
		this.leaseTime = requirePositive(leaseTime, "leaseTime");
		this.retryNanos = nanos(requirePositive(retryInterval, "retryInterval"));
	}

	/** 抢锁，不等；拿到以后一直占到关闭为止（自动续期） */
	public Optional<LockHandle> tryLock(String key) {
		return tryLock(key, NO_WAIT, null);
	}

	/** 抢锁，最多等 {@code waitTime}；拿到以后一直占到关闭为止（自动续期） */
	public Optional<LockHandle> tryLock(String key, Duration waitTime) {
		return tryLock(key, waitTime, null);
	}

	/**
	 * 抢锁。
	 *
	 * @param key 锁名，不用带统一前缀
	 * @param waitTime 抢不到时最多等多久；{@code null}、0 或负数都是不等
	 * @param leaseTime 固定租期，到时间一定放开、不续期；{@code null}、0 或负数表示一直占到关闭为止（自动续期）
	 * @return 拿到了返回句柄；没拿到（包括等待时线程被中断）返回空
	 */
	public Optional<LockHandle> tryLock(String key, Duration waitTime, Duration leaseTime) {
		if (key == null || key.isBlank()) {
			throw new IllegalArgumentException("锁名不能为空");
		}
		String fullKey = keyPrefix + key;
		Held reentered = reenter(fullKey);
		if (reentered != null) {
			return Optional.of(new Handle(key, reentered));
		}
		boolean autoRenew = leaseTime == null || leaseTime.isZero() || leaseTime.isNegative();
		Duration ttl = autoRenew ? this.leaseTime : leaseTime;
		long waitNanos = waitTime == null || waitTime.isNegative() ? 0 : nanos(waitTime);
		long deadline = System.nanoTime() + waitNanos;
		while (true) {
			// 有效期从发请求之前算起，本地认为的到期时间只会比 Redis 里的早，不会晚
			long start = System.nanoTime();
			String token = instanceId + ":" + sequence.incrementAndGet();
			if (client.tryAcquire(fullKey, token, ttl)) {
				return Optional.of(new Handle(key, hold(fullKey, token, ttl, autoRenew, start)));
			}
			long remaining = deadline - System.nanoTime();
			if (remaining <= 0 || !pause(Math.min(remaining, nextRetryNanos()))) {
				return Optional.empty();
			}
		}
	}

	/** 拿到锁再执行并返回结果，执行完释放；最多等 {@code waitTime}，等不到抛 {@link LockFailedException} */
	public <T> T execute(String key, Duration waitTime, Supplier<T> action) {
		return execute(key, waitTime, null, action);
	}

	/** 同 {@link #execute(String, Duration, Supplier)}，租期的含义见 {@link #tryLock(String, Duration, Duration)} */
	public <T> T execute(String key, Duration waitTime, Duration leaseTime, Supplier<T> action) {
		Objects.requireNonNull(action, "action");
		LockHandle handle = tryLock(key, waitTime, leaseTime)
			.orElseThrow(() -> new LockFailedException(key, DEFAULT_FAIL_MESSAGE));
		try (handle) {
			return action.get();
		}
	}

	/** 拿到锁再执行，执行完释放；最多等 {@code waitTime}，等不到抛 {@link LockFailedException} */
	public void run(String key, Duration waitTime, Runnable action) {
		run(key, waitTime, null, action);
	}

	/** 同 {@link #run(String, Duration, Runnable)}，租期的含义见 {@link #tryLock(String, Duration, Duration)} */
	public void run(String key, Duration waitTime, Duration leaseTime, Runnable action) {
		Objects.requireNonNull(action, "action");
		execute(key, waitTime, leaseTime, () -> {
			action.run();
			return null;
		});
	}

	/**
	 * 抢得到就执行，抢不到就跳过，不等也不报错。定时任务部署多个实例时用它，每次只有一个实例真正执行。
	 *
	 * @return 执行了返回 true；被别人占着、跳过了返回 false
	 */
	public boolean tryRun(String key, Runnable action) {
		Objects.requireNonNull(action, "action");
		Optional<LockHandle> handle = tryLock(key);
		if (handle.isEmpty()) {
			return false;
		}
		try (LockHandle held = handle.get()) {
			action.run();
		}
		return true;
	}

	/** 应用关闭时停掉续期线程。还没释放的锁不在这里强行放：持有它的代码可能还在执行，等它自己关闭或者到期 */
	@Override
	public void destroy() {
		ScheduledThreadPoolExecutor executor;
		synchronized (renewerMonitor) {
			destroyed = true;
			executor = renewer;
			renewer = null;
		}
		if (executor != null) {
			executor.shutdownNow();
		}
	}

	private Held reenter(String fullKey) {
		Held held = holds.get(fullKey);
		if (held == null || held.owner != Thread.currentThread()) {
			return null;
		}
		synchronized (held) {
			if (held.released || !held.valid(System.nanoTime())) {
				return null;
			}
			held.count++;
			return held;
		}
	}

	private Held hold(String fullKey, String token, Duration ttl, boolean autoRenew, long startNanos) {
		Held held = new Held(fullKey, token, ttl, autoRenew, Thread.currentThread(), startNanos);
		// 上一条记录如果还在，只可能是已经失效、持有者还没关闭的：直接盖掉，它关闭时按对象比对，不会删错
		holds.put(fullKey, held);
		if (autoRenew) {
			try {
				startRenewing(held);
			} catch (RuntimeException | Error e) {
				release(held);
				throw e;
			}
		}
		return held;
	}

	private void startRenewing(Held held) {
		long periodMillis = Math.max(1, held.ttl.toMillis() / 3);
		ScheduledFuture<?> task = renewer().scheduleWithFixedDelay(() -> renew(held), periodMillis, periodMillis,
			TimeUnit.MILLISECONDS);
		synchronized (held) {
			if (held.released || held.lost) {
				task.cancel(false);
			} else {
				held.renewTask = task;
			}
		}
	}

	private void renew(Held held) {
		synchronized (held) {
			if (held.released || held.lost) {
				return;
			}
		}
		long start = System.nanoTime();
		boolean renewed;
		try {
			renewed = client.renew(held.key, held.token, held.ttl);
		} catch (RuntimeException e) {
			log.warn("锁 {} 续期出错，下一轮再试：{}", held.key, e.toString());
			return;
		}
		if (renewed) {
			held.validUntilNanos = start + nanos(held.ttl);
			return;
		}
		ScheduledFuture<?> task;
		synchronized (held) {
			if (held.released) {
				// 续期和释放撞在一起、释放先到：锁是自己放掉的，不是丢了
				return;
			}
			held.lost = true;
			task = held.renewTask;
			held.renewTask = null;
		}
		if (task != null) {
			task.cancel(false);
		}
		log.warn("锁 {} 已经不在自己手里（到期或者被清掉了），停止续期；持有它的代码还在执行，这期间别的实例可能进入", held.key);
	}

	private void release(Held held) {
		ScheduledFuture<?> task;
		boolean wasValid;
		synchronized (held) {
			if (held.released || --held.count > 0) {
				return;
			}
			held.released = true;
			wasValid = held.valid(System.nanoTime());
			task = held.renewTask;
			held.renewTask = null;
		}
		if (task != null) {
			task.cancel(false);
		}
		holds.remove(held.key, held);
		boolean released;
		try {
			released = client.release(held.key, held.token);
		} catch (RuntimeException e) {
			log.warn("释放锁 {} 出错，它会在租期到了以后自己放开：{}", held.key, e.toString());
			return;
		}
		if (released || held.lost) {
			// 正常释放；或者续期时已经发现锁丢了，当时报过一次
			return;
		}
		long heldMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - held.acquiredAtNanos);
		if (wasValid) {
			log.warn("释放锁 {} 时它已经不在自己手里（存锁的地方数据被清掉了），占用的 {} 毫秒里别的实例可能已经进入", held.key, heldMillis);
		} else if (held.autoRenew) {
			log.warn("锁 {} 续期一直没成功，占用 {} 毫秒后已经到期，到期以后别的实例可能已经进入", held.key, heldMillis);
		} else {
			log.warn("锁 {} 占用了 {} 毫秒，超过了租期 {} 毫秒，到期以后别的实例可能已经进入；调大租期，或者不传租期让它自动续", held.key,
				heldMillis, held.ttl.toMillis());
		}
	}

	private ScheduledThreadPoolExecutor renewer() {
		ScheduledThreadPoolExecutor executor = renewer;
		if (executor != null) {
			return executor;
		}
		synchronized (renewerMonitor) {
			if (destroyed) {
				throw new IllegalStateException("应用正在关闭，不能再加需要自动续期的锁");
			}
			if (renewer == null) {
				// 续期只是一条很轻的 Redis 命令，一个守护线程够用；用到自动续期时才创建
				ScheduledThreadPoolExecutor created = new ScheduledThreadPoolExecutor(1, runnable -> {
					Thread thread = new Thread(runnable, "mugsun-lock-renew");
					thread.setDaemon(true);
					return thread;
				});
				created.setRemoveOnCancelPolicy(true);
				renewer = created;
			}
			return renewer;
		}
	}

	private long nextRetryNanos() {
		// 在间隔的 0.5 到 1.5 倍之间随机，避免一批等待者同时醒来一起抢
		return retryNanos / 2 + ThreadLocalRandom.current().nextLong(retryNanos + 1);
	}

	private static boolean pause(long nanos) {
		try {
			TimeUnit.NANOSECONDS.sleep(nanos);
			return true;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return false;
		}
	}

	private static Duration requirePositive(Duration value, String name) {
		if (value == null || value.isZero() || value.isNegative()) {
			throw new IllegalArgumentException(name + " 必须大于 0");
		}
		return value;
	}

	private static long nanos(Duration duration) {
		try {
			return Math.min(duration.toNanos(), MAX_NANOS);
		} catch (ArithmeticException e) {
			return MAX_NANOS;
		}
	}

	/** 本进程拿着的一把锁。重入时只加计数，计数回到 0 才真正释放 */
	private static final class Held {

		final String key;

		final String token;

		final Duration ttl;

		final boolean autoRenew;

		final Thread owner;

		final long acquiredAtNanos;

		/** 本地认为锁有效到什么时候：加锁或最近一次续期成功的发起时刻加租期 */
		volatile long validUntilNanos;

		/** 续期时发现锁已经不在自己手里 */
		volatile boolean lost;

		int count = 1;

		boolean released;

		ScheduledFuture<?> renewTask;

		Held(String key, String token, Duration ttl, boolean autoRenew, Thread owner, long startNanos) {
			this.key = key;
			this.token = token;
			this.ttl = ttl;
			this.autoRenew = autoRenew;
			this.owner = owner;
			this.acquiredAtNanos = startNanos;
			this.validUntilNanos = startNanos + nanos(ttl);
		}

		boolean valid(long now) {
			return !lost && validUntilNanos - now > 0;
		}

		synchronized boolean heldNow() {
			return !released && valid(System.nanoTime());
		}
	}

	private final class Handle implements LockHandle {

		private final String key;

		private final Held held;

		private final AtomicBoolean closed = new AtomicBoolean();

		Handle(String key, Held held) {
			this.key = key;
			this.held = held;
		}

		@Override
		public String key() {
			return key;
		}

		@Override
		public boolean isHeld() {
			return !closed.get() && held.heldNow();
		}

		@Override
		public void close() {
			if (closed.compareAndSet(false, true)) {
				release(held);
			}
		}
	}
}
