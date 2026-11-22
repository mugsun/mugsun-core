package com.mugsun.core.lock.config;

import com.mugsun.core.lock.template.LockTemplate;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 锁的配置。全部有默认值，一项都不配也能用（默认值和 {@link LockTemplate} 里的常量一致，这里写字面量是为了让 IDE 的配置提示能显示默认值）：
 *
 * <pre>
 * mugsun:
 *   lock:
 *     type: auto          # auto：有 Redis 用 Redis，没有就用单机锁；redis：必须用 Redis；local：只用单机锁
 *     key-prefix: "lock:"  # 锁在 Redis 里的统一前缀
 *     lease-time: 30s     # 不指定租期时的租期，持有期间自动续
 *     retry-interval: 100ms
 * </pre>
 */
@ConfigurationProperties(prefix = "mugsun.lock")
public class LockProperties {

	/** 是否启用锁组件。关掉以后不注册任何东西，@DistributedLock 不生效。 */
	private boolean enabled = true;

	/** 锁存在哪里。默认 auto：应用里有 Redis 就用 Redis，没有就退回单机锁。 */
	private Type type = Type.AUTO;

	/** 锁名前面统一加的前缀。几个应用共用一个 Redis、又不想互相挡住时，各自配不同的前缀。 */
	private String keyPrefix = "lock:";

	/** 不指定租期时的租期：持有期间每过三分之一自动续一次，进程崩溃后最多这么久放开。 */
	private Duration leaseTime = Duration.ofSeconds(30);

	/** 等锁时隔多久再抢一次。 */
	private Duration retryInterval = Duration.ofMillis(100);

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public Type getType() {
		return type;
	}

	public void setType(Type type) {
		this.type = type;
	}

	public String getKeyPrefix() {
		return keyPrefix;
	}

	public void setKeyPrefix(String keyPrefix) {
		this.keyPrefix = keyPrefix;
	}

	public Duration getLeaseTime() {
		return leaseTime;
	}

	public void setLeaseTime(Duration leaseTime) {
		this.leaseTime = leaseTime;
	}

	public Duration getRetryInterval() {
		return retryInterval;
	}

	public void setRetryInterval(Duration retryInterval) {
		this.retryInterval = retryInterval;
	}

	/** 锁存在哪里 */
	public enum Type {

		/** 应用里有 Redis 就用 Redis，没有就用单机锁 */
		AUTO,

		/** 必须用 Redis：找不到 Redis 连接时启动失败，不退回单机锁。部署多个实例的生产环境建议这样配 */
		REDIS,

		/** 只用单机锁：只在一个实例内互斥 */
		LOCAL
	}
}
