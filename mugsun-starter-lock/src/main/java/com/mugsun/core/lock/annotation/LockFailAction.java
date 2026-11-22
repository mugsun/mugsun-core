package com.mugsun.core.lock.annotation;

/** 没抢到锁怎么办 */
public enum LockFailAction {

	/** 抛 {@link com.mugsun.core.lock.exception.LockFailedException}，接口返回一次普通的业务失败 */
	THROW,

	/**
	 * 不执行、不报错，方法直接返回（有返回值的返回 null）。给定时任务用：别的实例已经在执行，这一次就跳过。
	 * 返回值是 int、boolean 这类基本类型的方法不能用，因为没有「空」可以返回。
	 */
	SKIP
}
