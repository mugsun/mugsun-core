package com.mugsun.core.lock.template;

import com.mugsun.core.lock.client.LocalLockClient;
import com.mugsun.core.lock.client.LockClient;
import com.mugsun.core.lock.exception.LockFailedException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.fail;

@DisplayName("加锁模板")
class LockTemplateTest {

	private final StubLockClient client = new StubLockClient();

	/** 默认租期 300 毫秒，所以自动续期每 100 毫秒一次；等锁时每 20 毫秒左右重抢一次 */
	private final LockTemplate template = new LockTemplate(client, "lock:", Duration.ofMillis(300),
		Duration.ofMillis(20));

	private final ExecutorService other = Executors.newSingleThreadExecutor();

	@AfterEach
	void tearDown() {
		template.destroy();
		other.shutdownNow();
	}

	@Test
	@DisplayName("拿到锁以后别的线程抢不到，关闭以后才抢得到")
	void exclusiveAcrossThreads() throws Exception {
		LockHandle held = template.tryLock("order:1").orElseThrow();
		assertThat(held.isHeld()).isTrue();
		assertThat(inOtherThread(() -> template.tryLock("order:1").isPresent())).isFalse();

		held.close();
		assertThat(held.isHeld()).isFalse();
		assertThat(inOtherThread(() -> {
			Optional<LockHandle> lock = template.tryLock("order:1");
			lock.ifPresent(LockHandle::close);
			return lock.isPresent();
		})).isTrue();
		assertThat(client.store).isEmpty();
	}

	@Test
	@DisplayName("锁名前面加统一前缀，句柄上的名字不带前缀")
	void keyPrefix() {
		LockTemplate prefixed = new LockTemplate(client, "app:lock:", Duration.ofSeconds(30), Duration.ofMillis(20));
		try (LockHandle held = prefixed.tryLock("order:1").orElseThrow()) {
			assertThat(client.store).containsOnlyKeys("app:lock:order:1");
			assertThat(held.key()).isEqualTo("order:1");
		} finally {
			prefixed.destroy();
		}
	}

	@Test
	@DisplayName("同一个线程再抢同一把锁直接拿到，最外层关闭才真正释放；句柄重复关闭只算一次")
	void reentrant() throws Exception {
		LockHandle outer = template.tryLock("k").orElseThrow();
		LockHandle inner = template.tryLock("k").orElseThrow();
		assertThat(client.acquired).hasSize(1);

		inner.close();
		inner.close();
		assertThat(inner.isHeld()).isFalse();
		assertThat(outer.isHeld()).isTrue();
		assertThat(client.store).containsKey("lock:k");
		assertThat(inOtherThread(() -> template.tryLock("k").isPresent())).isFalse();

		outer.close();
		assertThat(client.store).isEmpty();
	}

	@Test
	@DisplayName("等锁：持有者放手以后，等待的线程拿到")
	void waitsUntilReleased() throws Exception {
		LockHandle held = template.tryLock("k").orElseThrow();
		Future<Boolean> waiter = other.submit(() -> {
			Optional<LockHandle> lock = template.tryLock("k", Duration.ofSeconds(10));
			lock.ifPresent(LockHandle::close);
			return lock.isPresent();
		});
		Thread.sleep(150);
		assertThat(waiter.isDone()).isFalse();

		held.close();
		assertThat(waiter.get(10, TimeUnit.SECONDS)).isTrue();
		assertThat(client.store).isEmpty();
	}

	@Test
	@DisplayName("等到时间还是抢不到，返回空，而且确实等够了时间")
	void waitTimesOut() throws Exception {
		LockHandle held = template.tryLock("k").orElseThrow();

		long[] elapsedMillis = new long[1];
		boolean got = inOtherThread(() -> {
			long start = System.nanoTime();
			boolean present = template.tryLock("k", Duration.ofMillis(200)).isPresent();
			elapsedMillis[0] = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
			return present;
		});

		assertThat(got).isFalse();
		assertThat(elapsedMillis[0]).isGreaterThanOrEqualTo(200);
		held.close();
	}

	@Test
	@DisplayName("等锁时线程被中断：马上返回空，中断标记保留")
	void interruptedWhileWaiting() throws Exception {
		LockHandle held = template.tryLock("k").orElseThrow();

		boolean[] result = inOtherThread(() -> {
			Thread.currentThread().interrupt();
			boolean present = template.tryLock("k", Duration.ofSeconds(60)).isPresent();
			return new boolean[] {present, Thread.interrupted()};
		});

		assertThat(result[0]).isFalse();
		assertThat(result[1]).isTrue();
		held.close();
	}

	@Test
	@DisplayName("execute、run：拿到锁执行并返回结果，执行完释放，抛异常也释放")
	void executeReleases() {
		Integer result = template.execute("k", LockTemplate.NO_WAIT, () -> {
			assertThat(client.store).containsKey("lock:k");
			return 42;
		});
		assertThat(result).isEqualTo(42);
		assertThat(client.store).isEmpty();

		assertThatThrownBy(() -> template.run("k", LockTemplate.NO_WAIT, () -> {
			throw new IllegalStateException("业务出错");
		})).isInstanceOf(IllegalStateException.class).hasMessage("业务出错");
		assertThat(client.store).isEmpty();
	}

	@Test
	@DisplayName("execute、run：抢不到抛 LockFailedException，业务代码不执行")
	void executeFailsWhenBusy() throws Exception {
		LockHandle held = template.tryLock("k").orElseThrow();
		AtomicBoolean ran = new AtomicBoolean();

		Throwable thrown = inOtherThread(
			() -> catchThrowable(() -> template.run("k", Duration.ofMillis(50), () -> ran.set(true))));

		assertThat(thrown).isInstanceOf(LockFailedException.class).hasMessage(LockTemplate.DEFAULT_FAIL_MESSAGE);
		assertThat(((LockFailedException) thrown).getKey()).isEqualTo("k");
		assertThat(ran).isFalse();
		held.close();
	}

	@Test
	@DisplayName("tryRun：抢不到就跳过并返回 false，抢到了执行并返回 true")
	void tryRunSkipsWhenBusy() throws Exception {
		LockHandle held = template.tryLock("job").orElseThrow();
		AtomicInteger runs = new AtomicInteger();

		assertThat(inOtherThread(() -> template.tryRun("job", runs::incrementAndGet))).isFalse();
		assertThat(runs).hasValue(0);

		held.close();
		assertThat(inOtherThread(() -> template.tryRun("job", runs::incrementAndGet))).isTrue();
		assertThat(runs).hasValue(1);
		assertThat(client.store).isEmpty();
	}

	@Test
	@DisplayName("不传租期：按默认租期上锁，持有期间自动续期，关闭以后不再续")
	void autoRenews() throws Exception {
		LockHandle held = template.tryLock("k").orElseThrow();
		assertThat(client.ttls).containsExactly(Duration.ofMillis(300));

		await(() -> client.renewals.get() >= 3);
		assertThat(held.isHeld()).isTrue();

		held.close();
		Thread.sleep(50);
		int afterClose = client.renewals.get();
		Thread.sleep(350);
		assertThat(client.renewals).hasValue(afterClose);
	}

	@Test
	@DisplayName("传了租期：固定租期，不续期，到时间句柄就失效")
	void fixedLeaseDoesNotRenew() throws Exception {
		LockHandle held = template.tryLock("k", LockTemplate.NO_WAIT, Duration.ofMillis(120)).orElseThrow();
		assertThat(client.ttls).containsExactly(Duration.ofMillis(120));

		Thread.sleep(400);
		assertThat(client.renewals).hasValue(0);
		assertThat(held.isHeld()).isFalse();
		held.close();
	}

	@Test
	@DisplayName("续期时发现锁已经不在了：句柄失效、停止续期，关闭时不会放掉后来者的锁")
	void detectsLostLock() throws Exception {
		LockHandle held = template.tryLock("k").orElseThrow();

		client.store.remove("lock:k");
		await(() -> !held.isHeld());
		Thread.sleep(50);
		int renewals = client.renewals.get();
		Thread.sleep(350);
		assertThat(client.renewals).hasValue(renewals);

		client.store.put("lock:k", "someone-else");
		held.close();
		assertThat(client.store).containsEntry("lock:k", "someone-else");
	}

	@Test
	@DisplayName("固定租期到期以后，同一个线程再抢不算重入，要重新抢；过期句柄关闭不影响新拿到的锁")
	void noReentryAfterExpiry() throws Exception {
		LockHandle stale = template.tryLock("k", LockTemplate.NO_WAIT, Duration.ofMillis(100)).orElseThrow();
		Thread.sleep(200);

		// 桩不会自己到期，锁在它那里还占着：按重入处理会直接拿到，这里必须是抢不到
		assertThat(template.tryLock("k")).isEmpty();

		client.store.remove("lock:k");
		LockHandle fresh = template.tryLock("k").orElseThrow();
		assertThat(client.acquired).hasSize(2);

		stale.close();
		assertThat(client.store).containsKey("lock:k");
		assertThat(inOtherThread(() -> template.tryLock("k").isPresent())).isFalse();

		fresh.close();
		assertThat(client.store).isEmpty();
	}

	@Test
	@DisplayName("别的线程替持有者关闭以后，持有者再抢是重新抢")
	void closedByAnotherThread() throws Exception {
		LockHandle held = template.tryLock("k").orElseThrow();

		inOtherThread(() -> {
			held.close();
			return null;
		});
		assertThat(client.store).isEmpty();

		try (LockHandle again = template.tryLock("k").orElseThrow()) {
			assertThat(again.isHeld()).isTrue();
			assertThat(client.acquired).hasSize(2);
		}
		assertThat(client.store).isEmpty();
	}

	@Test
	@DisplayName("释放时存锁的地方连不上：只记警告，不抛异常")
	void releaseFailureIsSwallowed() {
		LockHandle held = template.tryLock("k").orElseThrow();
		client.failOnRelease = new IllegalStateException("连不上");

		assertThatCode(held::close).doesNotThrowAnyException();
		assertThat(held.isHeld()).isFalse();
	}

	@Test
	@DisplayName("续期出错不算丢锁，恢复以后接着续")
	void renewFailureIsRetried() throws Exception {
		LockHandle held = template.tryLock("k").orElseThrow();
		client.failOnRenew = new IllegalStateException("连不上");
		await(() -> client.renewals.get() >= 1);

		client.failOnRenew = null;
		int before = client.renewals.get();
		await(() -> client.renewals.get() >= before + 2);
		assertThat(held.isHeld()).isTrue();
		held.close();
		assertThat(client.store).isEmpty();
	}

	@Test
	@DisplayName("模板关闭以后：自动续期的锁加不了也不留残余，固定租期的锁照常能用")
	void afterDestroy() {
		template.destroy();

		assertThatThrownBy(() -> template.tryLock("k")).isInstanceOf(IllegalStateException.class);
		assertThat(client.store).isEmpty();

		try (LockHandle held = template.tryLock("k", LockTemplate.NO_WAIT, Duration.ofSeconds(30)).orElseThrow()) {
			assertThat(held.isHeld()).isTrue();
			assertThat(client.store).containsKey("lock:k");
		}
		assertThat(client.store).isEmpty();
	}

	@Test
	@DisplayName("锁名为空、默认租期或重试间隔不是正数，直接报错")
	void validation() {
		assertThatThrownBy(() -> template.tryLock(" ")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> template.tryLock(null)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new LockTemplate(client, "lock:", Duration.ZERO, Duration.ofMillis(20)))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new LockTemplate(client, "lock:", Duration.ofSeconds(30), null))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("很多线程抢同一把锁：同一时刻只有一个在执行，一次都不丢")
	void mutualExclusionUnderContention() throws Exception {
		LockTemplate real = new LockTemplate(new LocalLockClient(), "lock:", Duration.ofSeconds(30),
			Duration.ofMillis(2));
		int threads = 16;
		int perThread = 25;
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		AtomicInteger inside = new AtomicInteger();
		AtomicInteger maxInside = new AtomicInteger();
		int[] counter = {0};
		try {
			List<Future<?>> futures = new ArrayList<>();
			for (int i = 0; i < threads; i++) {
				futures.add(pool.submit(() -> {
					for (int n = 0; n < perThread; n++) {
						real.run("counter", Duration.ofSeconds(60), () -> {
							maxInside.accumulateAndGet(inside.incrementAndGet(), Math::max);
							counter[0]++;
							inside.decrementAndGet();
						});
					}
				}));
			}
			for (Future<?> future : futures) {
				future.get(120, TimeUnit.SECONDS);
			}
		} finally {
			pool.shutdownNow();
			real.destroy();
		}

		assertThat(maxInside).hasValue(1);
		assertThat(counter[0]).isEqualTo(threads * perThread);
	}

	private <T> T inOtherThread(Callable<T> task) throws Exception {
		return other.submit(task).get(30, TimeUnit.SECONDS);
	}

	private static void await(BooleanSupplier condition) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
		while (!condition.getAsBoolean()) {
			if (System.nanoTime() - deadline > 0) {
				fail("等了 10 秒条件还没成立");
			}
			Thread.sleep(10);
		}
	}

	/** 可以随意摆布的假锁：不会自己到期，方便精确模拟「锁没了」「连不上」 */
	static final class StubLockClient implements LockClient {

		final ConcurrentHashMap<String, String> store = new ConcurrentHashMap<>();

		final List<String> acquired = new CopyOnWriteArrayList<>();

		final List<Duration> ttls = new CopyOnWriteArrayList<>();

		final AtomicInteger renewals = new AtomicInteger();

		volatile RuntimeException failOnRelease;

		volatile RuntimeException failOnRenew;

		@Override
		public boolean tryAcquire(String key, String token, Duration ttl) {
			if (store.putIfAbsent(key, token) != null) {
				return false;
			}
			acquired.add(key);
			ttls.add(ttl);
			return true;
		}

		@Override
		public boolean release(String key, String token) {
			RuntimeException failure = failOnRelease;
			if (failure != null) {
				throw failure;
			}
			return store.remove(key, token);
		}

		@Override
		public boolean renew(String key, String token, Duration ttl) {
			renewals.incrementAndGet();
			RuntimeException failure = failOnRenew;
			if (failure != null) {
				throw failure;
			}
			return token.equals(store.get(key));
		}
	}
}
