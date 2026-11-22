package com.mugsun.core.lock.exception;

import com.mugsun.core.tool.exception.ServiceException;

/**
 * 没抢到锁。它是 {@link ServiceException}，所以在接口里抛出来会被统一异常处理变成一次普通的业务失败，
 * 前端看到的是提示语，不是 500。
 */
public class LockFailedException extends ServiceException {

	private static final long serialVersionUID = 1L;

	private final String key;

	public LockFailedException(String key, String message) {
		super(message);
		this.key = key;
	}

	/** 没抢到的那把锁的名字，不带统一前缀；只用来排查，不会出现在给前端的提示语里 */
	public String getKey() {
		return key;
	}
}
