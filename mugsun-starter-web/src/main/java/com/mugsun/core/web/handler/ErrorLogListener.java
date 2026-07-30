package com.mugsun.core.web.handler;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 未捕获异常监听：全局异常处理兜底（500）处发布，业务侧实现可持久化错误日志。
 * <p>经 {@code ObjectProvider} 可缺省注入——starter 不耦合任何业务错误日志表；
 * 实现在请求线程内被同步调用，须自行异步落库避免阻塞响应。
 */
public interface ErrorLogListener {

	/** 兜底 500 异常触发（request 为当前请求，error 为未捕获异常） */
	void onError(HttpServletRequest request, Throwable error);
}
