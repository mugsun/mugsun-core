package com.mugsun.core.lock.config;

import com.mugsun.core.lock.aspect.DistributedLockAspect;
import com.mugsun.core.lock.client.LocalLockClient;
import com.mugsun.core.lock.client.LockClient;
import com.mugsun.core.lock.template.LockTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.expression.BeanFactoryResolver;
import org.springframework.util.ClassUtils;

/**
 * 分布式锁的装配。引入本模块就能用 {@link LockTemplate} 和
 * {@link com.mugsun.core.lock.annotation.DistributedLock}，不需要任何配置：
 * 应用里有 Redis 连接就把锁放在 Redis 里（多个实例之间互斥），没有就退回单机锁并在启动时提醒一句。
 *
 * <p>三个 Bean 都可以被应用自己定义的同类型 Bean 替换；比如自己提供一个 {@link LockClient}，锁就可以放到别的地方。
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "mugsun.lock", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(LockProperties.class)
public class MugsunLockAutoConfiguration {

	private static final Logger log = LoggerFactory.getLogger(MugsunLockAutoConfiguration.class);

	private static final String REDIS_TEMPLATE_CLASS = "org.springframework.data.redis.core.StringRedisTemplate";

	@Bean
	@ConditionalOnMissingBean
	public LockClient lockClient(LockProperties properties, BeanFactory beanFactory) {
		LockProperties.Type type = properties.getType();
		if (type == LockProperties.Type.LOCAL) {
			return new LocalLockClient();
		}
		// 先确认 Redis 的类在不在，再去碰引用了这些类的 RedisLockClientFactory：应用没引 Redis 时不会因为找不到类而启动失败
		ClassLoader classLoader = beanFactory instanceof ConfigurableBeanFactory configurable
			? configurable.getBeanClassLoader() : null;
		LockClient redis = ClassUtils.isPresent(REDIS_TEMPLATE_CLASS, classLoader)
			? RedisLockClientFactory.create(beanFactory) : null;
		if (redis != null) {
			return redis;
		}
		if (type == LockProperties.Type.REDIS) {
			throw new IllegalStateException("mugsun.lock.type=redis，但是应用里找不到可用的 Redis 连接："
				+ "没有引入 spring-boot-starter-data-redis，或者 Redis 的自动配置被排除了，或者有多个连接而没有指定主连接");
		}
		log.warn("应用里没有 Redis 连接，锁用的是单机锁：只在本实例内互斥。部署多个实例时请引入 spring-boot-starter-data-redis；"
			+ "确定只部署一个实例，可以配置 mugsun.lock.type=local 去掉这条提醒");
		return new LocalLockClient();
	}

	@Bean
	@ConditionalOnMissingBean
	public LockTemplate lockTemplate(LockClient lockClient, LockProperties properties) {
		return new LockTemplate(lockClient, properties.getKeyPrefix(), properties.getLeaseTime(),
			properties.getRetryInterval());
	}

	@Bean
	@ConditionalOnMissingBean
	public DistributedLockAspect distributedLockAspect(LockTemplate lockTemplate, BeanFactory beanFactory) {
		return new DistributedLockAspect(lockTemplate, new BeanFactoryResolver(beanFactory));
	}
}
