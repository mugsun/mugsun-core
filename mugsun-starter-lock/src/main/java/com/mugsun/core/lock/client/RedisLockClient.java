package com.mugsun.core.lock.client;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;

/**
 * 锁放在 Redis 里，多个实例之间互斥。
 *
 * <p>抢锁是一条 {@code SET key token NX PX 毫秒数}；释放和续期各是一段脚本，先比对令牌再删除或改到期时间，
 * 比对和操作在 Redis 里一次做完。每把锁只用一个键，Redis 集群也能用。
 *
 * <p>它对付不了的情况：持有者的进程停顿（比如长时间的垃圾回收）超过了到期时间，锁会被别人抢走，而持有者醒来以后并不知道。
 * 要求「绝对不能有两个同时执行」的地方，数据库里还要有唯一约束或版本号兜底。
 */
public class RedisLockClient implements LockClient {

	private static final RedisScript<Long> RELEASE = new DefaultRedisScript<>(
		"if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
		Long.class);

	private static final RedisScript<Long> RENEW = new DefaultRedisScript<>(
		"if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('pexpire', KEYS[1], ARGV[2]) "
			+ "else return 0 end",
		Long.class);

	private final StringRedisTemplate redis;

	public RedisLockClient(StringRedisTemplate redis) {
		this.redis = redis;
	}

	@Override
	public boolean tryAcquire(String key, String token, Duration ttl) {
		return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key, token, ttl));
	}

	@Override
	public boolean release(String key, String token) {
		Long released = redis.execute(RELEASE, List.of(key), token);
		return released != null && released > 0;
	}

	@Override
	public boolean renew(String key, String token, Duration ttl) {
		Long renewed = redis.execute(RENEW, List.of(key), token, String.valueOf(ttl.toMillis()));
		return renewed != null && renewed > 0;
	}
}
