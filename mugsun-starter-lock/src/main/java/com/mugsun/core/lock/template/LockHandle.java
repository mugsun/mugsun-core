package com.mugsun.core.lock.template;

/**
 * 拿到手的一把锁。用完要关闭，关闭就是释放；放在 try-with-resources 里最省事：
 *
 * <pre>{@code
 * Optional<LockHandle> lock = lockTemplate.tryLock("order:" + id);
 * if (lock.isEmpty()) {
 *     return;
 * }
 * try (LockHandle held = lock.get()) {
 *     // 这里同一时刻只有一个在执行
 * }
 * }</pre>
 */
public interface LockHandle extends AutoCloseable {

	/** 锁名，就是抢锁时传进来的那个，不带统一前缀 */
	String key();

	/**
	 * 锁是不是还在自己手里。关闭以后、固定租期到期以后、续期时发现锁已经不在了，都返回 false。
	 * 执行时间长、又不能接受两个同时执行的代码，可以在提交结果之前问一次。
	 */
	boolean isHeld();

	/** 释放。重复调用没有副作用；释放时连不上 Redis 只记一条警告，不抛异常，锁到期后自己放开 */
	@Override
	void close();
}
