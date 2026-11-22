package com.mugsun.core.lock.config;

import com.mugsun.core.lock.aspect.DistributedLockAspect;
import com.mugsun.core.lock.client.LocalLockClient;
import com.mugsun.core.lock.client.LockClient;
import com.mugsun.core.lock.client.RedisLockClient;
import com.mugsun.core.lock.template.LockHandle;
import com.mugsun.core.lock.template.LockTemplate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@DisplayName("锁的自动装配")
class MugsunLockAutoConfigurationTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(AopAutoConfiguration.class, MugsunLockAutoConfiguration.class));

	@Test
	@DisplayName("已经登记在自动配置清单里，引入模块就生效")
	void registered() {
		assertThat(ImportCandidates.load(AutoConfiguration.class, getClass().getClassLoader()).getCandidates())
			.contains(MugsunLockAutoConfiguration.class.getName());
	}

	@Test
	@DisplayName("配置项的默认值和模板里的常量一致")
	void defaultsMatchTemplateConstants() {
		LockProperties properties = new LockProperties();

		assertThat(properties.isEnabled()).isTrue();
		assertThat(properties.getType()).isEqualTo(LockProperties.Type.AUTO);
		assertThat(properties.getKeyPrefix()).isEqualTo(LockTemplate.DEFAULT_KEY_PREFIX);
		assertThat(properties.getLeaseTime()).isEqualTo(LockTemplate.DEFAULT_LEASE_TIME);
		assertThat(properties.getRetryInterval()).isEqualTo(LockTemplate.DEFAULT_RETRY_INTERVAL);
	}

	@Test
	@DisplayName("什么都不配、应用里也没有 Redis：用单机锁，模板和注解切面都在，马上能加锁")
	void localByDefaultWithoutRedis() {
		runner.run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context.getBean(LockClient.class)).isInstanceOf(LocalLockClient.class);
			assertThat(context).hasSingleBean(LockTemplate.class).hasSingleBean(DistributedLockAspect.class);

			AtomicBoolean ran = new AtomicBoolean();
			assertThat(context.getBean(LockTemplate.class).tryRun("job", () -> ran.set(true))).isTrue();
			assertThat(ran).isTrue();
		});
	}

	@Test
	@DisplayName("应用里有 StringRedisTemplate：用 Redis 锁")
	void redisWhenTemplatePresent() {
		runner.withUserConfiguration(RedisTemplateConfig.class)
			.run(context -> assertThat(context.getBean(LockClient.class)).isInstanceOf(RedisLockClient.class));
	}

	@Test
	@DisplayName("应用里只有 Redis 连接工厂、没有 StringRedisTemplate：也用 Redis 锁")
	void redisWhenOnlyConnectionFactoryPresent() {
		runner.withUserConfiguration(ConnectionFactoryConfig.class)
			.run(context -> assertThat(context.getBean(LockClient.class)).isInstanceOf(RedisLockClient.class));
	}

	@Test
	@DisplayName("type=local：有 Redis 也用单机锁")
	void localWhenForced() {
		runner.withUserConfiguration(RedisTemplateConfig.class).withPropertyValues("mugsun.lock.type=local")
			.run(context -> assertThat(context.getBean(LockClient.class)).isInstanceOf(LocalLockClient.class));
	}

	@Test
	@DisplayName("type=redis 但应用里没有 Redis：启动失败并说清原因，不悄悄退回单机锁")
	void failsWhenRedisRequiredButMissing() {
		runner.withPropertyValues("mugsun.lock.type=redis").run(context -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).rootCause().isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("mugsun.lock.type=redis");
		});
	}

	@Test
	@DisplayName("类路径上根本没有 Redis 的类：照常启动，用单机锁")
	void localWhenRedisClassesAbsent() {
		runner.withClassLoader(new FilteredClassLoader("org.springframework.data.redis")).run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context.getBean(LockClient.class)).isInstanceOf(LocalLockClient.class);
		});
	}

	@Test
	@DisplayName("enabled=false：什么都不注册")
	void disabled() {
		runner.withPropertyValues("mugsun.lock.enabled=false").run(context -> {
			assertThat(context).doesNotHaveBean(LockClient.class).doesNotHaveBean(LockTemplate.class)
				.doesNotHaveBean(DistributedLockAspect.class);
		});
	}

	@Test
	@DisplayName("应用自己定义了 LockClient：用应用的；前缀和默认租期按配置来")
	void customClientAndProperties() {
		runner.withUserConfiguration(CustomClientConfig.class)
			.withPropertyValues("mugsun.lock.key-prefix=shop:lock:", "mugsun.lock.lease-time=45s",
				"mugsun.lock.retry-interval=10ms")
			.run(context -> {
				CapturingLockClient client = context.getBean(CapturingLockClient.class);
				assertThat(context).hasSingleBean(LockClient.class);

				try (LockHandle held = context.getBean(LockTemplate.class).tryLock("order:1").orElseThrow()) {
					assertThat(held.key()).isEqualTo("order:1");
				}

				assertThat(client.keys).containsExactly("shop:lock:order:1");
				assertThat(client.ttls).containsExactly(Duration.ofSeconds(45));
			});
	}

	@Configuration(proxyBeanMethods = false)
	static class RedisTemplateConfig {

		@Bean
		StringRedisTemplate stringRedisTemplate() {
			return new StringRedisTemplate(mock(RedisConnectionFactory.class));
		}
	}

	@Configuration(proxyBeanMethods = false)
	static class ConnectionFactoryConfig {

		@Bean
		RedisConnectionFactory redisConnectionFactory() {
			return mock(RedisConnectionFactory.class);
		}
	}

	@Configuration(proxyBeanMethods = false)
	static class CustomClientConfig {

		@Bean
		CapturingLockClient capturingLockClient() {
			return new CapturingLockClient();
		}
	}

	static class CapturingLockClient implements LockClient {

		final List<String> keys = new CopyOnWriteArrayList<>();

		final List<Duration> ttls = new CopyOnWriteArrayList<>();

		@Override
		public boolean tryAcquire(String key, String token, Duration ttl) {
			keys.add(key);
			ttls.add(ttl);
			return true;
		}

		@Override
		public boolean release(String key, String token) {
			return true;
		}

		@Override
		public boolean renew(String key, String token, Duration ttl) {
			return true;
		}
	}
}
