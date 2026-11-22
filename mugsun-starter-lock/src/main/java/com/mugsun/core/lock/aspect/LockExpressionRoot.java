package com.mugsun.core.lock.aspect;

import java.lang.reflect.Method;

/**
 * key 表达式里的 {@code #root}：{@code #root.methodName}、{@code #root.args[0]}、{@code #root.target}、
 * {@code #root.targetClass}。平时直接写参数名（{@code #orderId}）就够了，用不到它。
 */
public class LockExpressionRoot {

	private final Method method;

	private final Object[] args;

	private final Object target;

	public LockExpressionRoot(Method method, Object[] args, Object target) {
		this.method = method;
		this.args = args;
		this.target = target;
	}

	public Method getMethod() {
		return method;
	}

	public String getMethodName() {
		return method.getName();
	}

	public Object[] getArgs() {
		return args;
	}

	public Object getTarget() {
		return target;
	}

	public Class<?> getTargetClass() {
		return target != null ? target.getClass() : method.getDeclaringClass();
	}
}
