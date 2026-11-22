package com.mugsun.core.lock.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("单机锁")
class LocalLockClientTest {

	private static final Duration TTL = Duration.ofSeconds(10);

	/** 能拨动的时钟：不用真的等 10 秒 */
	private final AtomicLong now = new AtomicLong(1_000);

	private final LocalLockClient client = new LocalLockClient(now::get);

	@Test
	@DisplayName("同一把锁第二个人抢不到，释放以后才抢得到；不同的锁互不影响")
	void exclusive() {
		assertThat(client.tryAcquire("k", "a", TTL)).isTrue();
		assertThat(client.tryAcquire("k", "b", TTL)).isFalse();
		assertThat(client.tryAcquire("other", "b", TTL)).isTrue();

		assertThat(client.release("k", "a")).isTrue();
		assertThat(client.tryAcquire("k", "b", TTL)).isTrue();
	}

	@Test
	@DisplayName("令牌不对，放不掉也续不了别人的锁")
	void needsOwnToken() {
		client.tryAcquire("k", "a", TTL);

		assertThat(client.release("k", "b")).isFalse();
		assertThat(client.renew("k", "b", TTL)).isFalse();
		assertThat(client.tryAcquire("k", "b", TTL)).isFalse();
		assertThat(client.release("missing", "a")).isFalse();
		assertThat(client.renew("missing", "a", TTL)).isFalse();
	}

	@Test
	@DisplayName("到期以后别人能抢到，原持有者再释放、续期都不生效，也不会放掉新持有者的锁")
	void expires() {
		client.tryAcquire("k", "a", TTL);
		advance(TTL.minusMillis(1));
		assertThat(client.tryAcquire("k", "b", TTL)).isFalse();

		advance(Duration.ofMillis(1));
		assertThat(client.renew("k", "a", TTL)).isFalse();
		assertThat(client.tryAcquire("k", "b", TTL)).isTrue();
		assertThat(client.release("k", "a")).isFalse();
		assertThat(client.tryAcquire("k", "c", TTL)).isFalse();
	}

	@Test
	@DisplayName("到期的锁自己去释放，返回没放掉，记录顺手清掉")
	void releaseAfterExpiry() {
		client.tryAcquire("k", "a", TTL);
		advance(TTL);

		assertThat(client.release("k", "a")).isFalse();
		assertThat(client.size()).isZero();
	}

	@Test
	@DisplayName("续期把到期时间从续的那一刻重新算")
	void renewExtends() {
		client.tryAcquire("k", "a", TTL);
		advance(Duration.ofSeconds(8));
		assertThat(client.renew("k", "a", TTL)).isTrue();

		// 距离加锁已经 16 秒，不续期的话早就到期了
		advance(Duration.ofSeconds(8));
		assertThat(client.tryAcquire("k", "b", TTL)).isFalse();

		advance(Duration.ofSeconds(2));
		assertThat(client.tryAcquire("k", "b", TTL)).isTrue();
	}

	@Test
	@DisplayName("到期又没人再抢的记录会被清掉，不会越积越多")
	void purgesExpired() {
		for (int i = 0; i < 2000; i++) {
			client.tryAcquire("order:" + i, "t", TTL);
		}
		assertThat(client.size()).isEqualTo(2000);

		advance(TTL);
		client.tryAcquire("order:new", "t", TTL);

		assertThat(client.size()).isEqualTo(1);
	}

	@Test
	@DisplayName("很多线程同时抢同一把锁，只有一个抢到")
	void onlyOneWins() throws Exception {
		LocalLockClient real = new LocalLockClient();
		int threads = 32;
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		try {
			for (int round = 0; round < 50; round++) {
				String key = "k" + round;
				CountDownLatch ready = new CountDownLatch(threads);
				CountDownLatch go = new CountDownLatch(1);
				CountDownLatch done = new CountDownLatch(threads);
				AtomicInteger winners = new AtomicInteger();
				for (int i = 0; i < threads; i++) {
					String token = "t" + i;
					pool.execute(() -> {
						ready.countDown();
						try {
							go.await();
							if (real.tryAcquire(key, token, TTL)) {
								winners.incrementAndGet();
							}
						} catch (InterruptedException e) {
							Thread.currentThread().interrupt();
						} finally {
							done.countDown();
						}
					});
				}
				assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
				go.countDown();
				assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
				assertThat(winners).hasValue(1);
			}
		} finally {
			pool.shutdownNow();
		}
	}

	private void advance(Duration duration) {
		now.addAndGet(duration.toNanos());
	}
}
