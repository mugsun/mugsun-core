package com.mugsun.core.lock.client;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * 锁放在本进程的内存里，只在一个实例内互斥。给没有 Redis 的本机开发、单实例部署和测试用；
 * 部署多个实例时它拦不住别的实例，要换成 {@link RedisLockClient}。
 *
 * <p>到期、令牌比对的规则和 Redis 那一个保持一致：到期的锁等于没有，释放和续期只认令牌相同、还没到期的持有者。
 */
public class LocalLockClient implements LockClient {

	private record Holder(String token, long expireAtNanos) {

		boolean expired(long now) {
			return expireAtNanos - now <= 0;
		}
	}

	/** 表里的记录超过这个数就清一遍到期的：锁名带着业务编号时，到期但没人再抢的记录会一直留着 */
	private static final int PURGE_THRESHOLD = 1024;

	/** 两次清理至少隔这么久：同时持有的锁本来就很多时，不要每次抢锁都扫一遍 */
	private static final long PURGE_INTERVAL_NANOS = Duration.ofSeconds(1).toNanos();

	private final ConcurrentHashMap<String, Holder> locks = new ConcurrentHashMap<>();

	private final LongSupplier nanoClock;

	private volatile long lastPurgeNanos;

	public LocalLockClient() {
		this(System::nanoTime);
	}

	/** 测试用：换一个能拨动的时钟，不用真的等到期 */
	LocalLockClient(LongSupplier nanoClock) {
		this.nanoClock = nanoClock;
		this.lastPurgeNanos = nanoClock.getAsLong();
	}

	@Override
	public boolean tryAcquire(String key, String token, Duration ttl) {
		long now = nanoClock.getAsLong();
		long expireAt = now + ttl.toNanos();
		boolean[] acquired = {false};
		locks.compute(key, (k, current) -> {
			if (current == null || current.expired(now)) {
				acquired[0] = true;
				return new Holder(token, expireAt);
			}
			return current;
		});
		if (acquired[0] && locks.size() > PURGE_THRESHOLD && now - lastPurgeNanos >= PURGE_INTERVAL_NANOS) {
			lastPurgeNanos = now;
			locks.entrySet().removeIf(entry -> entry.getValue().expired(now));
		}
		return acquired[0];
	}

	@Override
	public boolean release(String key, String token) {
		long now = nanoClock.getAsLong();
		boolean[] released = {false};
		locks.computeIfPresent(key, (k, current) -> {
			if (!current.token().equals(token)) {
				return current;
			}
			// 到期的记录顺手删掉，但不算「放掉了」：到期时锁就已经不在这个持有者手里了
			released[0] = !current.expired(now);
			return null;
		});
		return released[0];
	}

	@Override
	public boolean renew(String key, String token, Duration ttl) {
		long now = nanoClock.getAsLong();
		long expireAt = now + ttl.toNanos();
		boolean[] renewed = {false};
		locks.computeIfPresent(key, (k, current) -> {
			if (current.token().equals(token) && !current.expired(now)) {
				renewed[0] = true;
				return new Holder(token, expireAt);
			}
			return current;
		});
		return renewed[0];
	}

	/** 测试用：表里现在有多少条记录（包括到期还没清的） */
	int size() {
		return locks.size();
	}
}
