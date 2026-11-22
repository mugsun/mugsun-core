package com.mugsun.core.lock.config;

import com.mugsun.core.lock.client.LockClient;
import com.mugsun.core.lock.client.RedisLockClient;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 从应用里找 Redis 连接。单独放一个类：只有确认 spring-data-redis 在类路径上以后才会加载它，
 * 没引 Redis 的应用不会碰到这里的 Redis 类。
 */
final class RedisLockClientFactory {

	private RedisLockClientFactory() {
	}

	/**
	 * @return 应用里有唯一（或指定了主）的 Redis 连接时返回 Redis 锁；没有、或者有多个却没指定主的，返回 null
	 */
	static LockClient create(BeanFactory beanFactory) {
		StringRedisTemplate template = beanFactory.getBeanProvider(StringRedisTemplate.class).getIfUnique();
		if (template == null) {
			RedisConnectionFactory connectionFactory = beanFactory.getBeanProvider(RedisConnectionFactory.class)
				.getIfUnique();
			if (connectionFactory == null) {
				return null;
			}
			template = new StringRedisTemplate(connectionFactory);
		}
		return new RedisLockClient(template);
	}
}
