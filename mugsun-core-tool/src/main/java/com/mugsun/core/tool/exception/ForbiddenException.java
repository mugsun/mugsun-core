package com.mugsun.core.tool.exception;

/**
 * 访问受限异常：映射真实 HTTP 403，携带业务提示语（租户越权 / 套餐外功能 / 无权限等）。
 */
public class ForbiddenException extends RuntimeException {

	public ForbiddenException(String message) {
		super(message);
	}
}
