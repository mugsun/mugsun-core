package com.mugsun.core.lock.client;

import com.mugsun.core.lock.template.LockHandle;
import com.mugsun.core.lock.template.LockTemplate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 拿真的 Redis 验证加锁、释放、续期这三段命令和脚本。没有 Docker 的机器上整个类自动跳过。
 */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("Redis 锁（真的 Redis）")
class RedisLockClientTest {

	private static final Duration TTL = Duration.ofSeconds(30);

	@Container
	static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
		.withExposedPorts(6379);

	private static LettuceConnectionFactory connectionFactory;

	private static StringRedisTemplate redis;

	private static RedisLockClient client;

	@BeforeAll
	static void connect() {
		connectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
		connectionFactory.afterPropertiesSet();
		redis = new StringRedisTemplate(connectionFactory);
		client = new RedisLockClient(redis);
	}

	@AfterAll
	static void disconnect() {
		if (connectionFactory != null) {
			connectionFactory.destroy();
		}
	}

	@Test
	@DisplayName("抢锁：第一个成功，第二个失败；Redis 里存的是持有者令牌，带着到期时间")
	void acquire() {
		String key = newKey();

		assertThat(client.tryAcquire(key, "a", TTL)).isTrue();
		assertThat(client.tryAcquire(key, "b", TTL)).isFalse();

		assertThat(redis.opsForValue().get(key)).isEqualTo("a");
		assertThat(redis.getExpire(key, TimeUnit.MILLISECONDS)).isBetween(20_000L, 30_000L);
	}

	@Test
	@DisplayName("释放：只有持有者放得掉，放掉以后别人能抢到")
	void release() {
		String key = newKey();
		client.tryAcquire(key, "a", TTL);

		assertThat(client.release(key, "b")).isFalse();
		assertThat(redis.opsForValue().get(key)).isEqualTo("a");

		assertThat(client.release(key, "a")).isTrue();
		assertThat(redis.hasKey(key)).isFalse();
		assertThat(client.release(key, "a")).isFalse();
		assertThat(client.tryAcquire(key, "b", TTL)).isTrue();
	}

	@Test
	@DisplayName("续期：只有持有者续得上，到期时间按新的租期重新算")
	void renew() {
		String key = newKey();
		client.tryAcquire(key, "a", Duration.ofSeconds(5));

		assertThat(client.renew(key, "b", Duration.ofSeconds(60))).isFalse();
		assertThat(redis.getExpire(key, TimeUnit.MILLISECONDS)).isLessThanOrEqualTo(5_000L);

		assertThat(client.renew(key, "a", Duration.ofSeconds(60))).isTrue();
		assertThat(redis.getExpire(key, TimeUnit.MILLISECONDS)).isBetween(50_000L, 60_000L);

		assertThat(client.renew(newKey(), "a", TTL)).isFalse();
	}

	@Test
	@DisplayName("到期以后自动放开，别人能抢到；原持有者再释放、续期，都碰不到新持有者的锁")
	void expires() throws Exception {
		String key = newKey();
		client.tryAcquire(key, "a", Duration.ofMillis(200));

		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
		while (Boolean.TRUE.equals(redis.hasKey(key)) && System.nanoTime() - deadline < 0) {
			Thread.sleep(20);
		}

		assertThat(client.tryAcquire(key, "b", TTL)).isTrue();
		assertThat(client.release(key, "a")).isFalse();
		assertThat(client.renew(key, "a", TTL)).isFalse();
		assertThat(redis.opsForValue().get(key)).isEqualTo("b");
	}

	@Test
	@DisplayName("配上模板：两个模板（相当于两个实例）抢同一把锁，同一时刻只有一个在执行，一次都不丢")
	void twoInstancesExcludeEachOther() throws Exception {
		String prefix = "test:" + UUID.randomUUID() + ":";
		LockTemplate first = new LockTemplate(client, prefix, TTL, Duration.ofMillis(5));
		LockTemplate second = new LockTemplate(client, prefix, TTL, Duration.ofMillis(5));
		int threads = 8;
		int perThread = 20;
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		AtomicInteger inside = new AtomicInteger();
		AtomicInteger maxInside = new AtomicInteger();
		int[] counter = {0};
		try {
			List<Future<?>> futures = new ArrayList<>();
			for (int i = 0; i < threads; i++) {
				LockTemplate template = i % 2 == 0 ? first : second;
				futures.add(pool.submit(() -> {
					for (int n = 0; n < perThread; n++) {
						template.run("counter", Duration.ofSeconds(60), () -> {
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
			first.destroy();
			second.destroy();
		}

		assertThat(maxInside).hasValue(1);
		assertThat(counter[0]).isEqualTo(threads * perThread);
		assertThat(redis.hasKey(prefix + "counter")).isFalse();
	}

	@Test
	@DisplayName("配上模板：不传租期时自动续期，占用时间超过默认租期也不会丢锁；关闭以后马上放开")
	void watchdogKeepsLockAlive() throws Exception {
		String prefix = "test:" + UUID.randomUUID() + ":";
		LockTemplate holder = new LockTemplate(client, prefix, Duration.ofMillis(600), Duration.ofMillis(20));
		LockTemplate rival = new LockTemplate(client, prefix, Duration.ofMillis(600), Duration.ofMillis(20));
		try {
			LockHandle held = holder.tryLock("report").orElseThrow();

			// 占 1.8 秒，是默认租期的三倍；不续期的话 0.6 秒就被别人抢走了
			assertThat(rival.tryLock("report", Duration.ofMillis(1800))).isEmpty();
			assertThat(held.isHeld()).isTrue();
			assertThat(redis.getExpire(prefix + "report", TimeUnit.MILLISECONDS)).isPositive();

			held.close();
			assertThat(redis.hasKey(prefix + "report")).isFalse();
			try (LockHandle next = rival.tryLock("report").orElseThrow()) {
				assertThat(next.isHeld()).isTrue();
			}
		} finally {
			holder.destroy();
			rival.destroy();
		}
	}

	private static String newKey() {
		return "test:lock:" + UUID.randomUUID();
	}
}
