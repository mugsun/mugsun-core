package com.mugsun.core.lock.client;

import java.time.Duration;

/**
 * 锁存在哪里：Redis（多个实例之间互斥）或者本进程的内存（只在一个实例内互斥）。
 * 业务代码不直接用这个接口，用 {@link com.mugsun.core.lock.template.LockTemplate} 或
 * {@link com.mugsun.core.lock.annotation.DistributedLock}。
 *
 * <p>每把锁都带一个持有者令牌和一个到期时间：释放和续期只认令牌相同的持有者，持有者崩溃以后锁到期自动放开。
 * 实现要保证三个方法各自是原子的。
 */
public interface LockClient {

	/**
	 * 抢锁，不等待。
	 *
	 * @param key 锁的完整名字
	 * @param token 持有者令牌，每次抢锁都不一样
	 * @param ttl 多久以后自动放开，必须大于 0
	 * @return 抢到返回 true；已经被别人拿着返回 false
	 */
	boolean tryAcquire(String key, String token, Duration ttl);

	/**
	 * 释放。只有令牌和当前持有者相同才释放，所以自己的锁到期、被别人抢走以后再调用，不会把别人的锁放掉。
	 *
	 * @return 确实放掉了返回 true
	 */
	boolean release(String key, String token);

	/**
	 * 续期：把到期时间改成从现在起的 {@code ttl}。只有令牌和当前持有者相同才续。
	 *
	 * @return 续上了返回 true；锁已经不在自己手里返回 false
	 */
	boolean renew(String key, String token, Duration ttl);
}
