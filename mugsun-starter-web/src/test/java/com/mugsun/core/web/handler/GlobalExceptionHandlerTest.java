package com.mugsun.core.web.handler;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import com.mugsun.core.tool.api.R;
import com.mugsun.core.tool.api.ResultCode;
import com.mugsun.core.tool.exception.ForbiddenException;
import com.mugsun.core.tool.exception.ServiceException;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 全局异常处理：客户端错误（4xx）与服务器故障（5xx）必须区分，且只有 5xx 进错误日志，
 * 否则扫描器探测会把错误日志刷满。
 */
class GlobalExceptionHandlerTest {

	private GlobalExceptionHandler handler;
	private List<Throwable> captured;

	@BeforeEach
	void setUp() {
		captured = new ArrayList<>();
		ErrorLogListener listener = (request, e) -> captured.add(e);
		handler = new GlobalExceptionHandler(providerOf(listener));
	}

	/** 仅提供 orderedStream 的最小 ObjectProvider（处理器只用到这一个方法） */
	private ObjectProvider<ErrorLogListener> providerOf(ErrorLogListener... listeners) {
		return new ObjectProvider<>() {
			@Override
			public ErrorLogListener getObject(Object... args) {
				return listeners[0];
			}

			@Override
			public ErrorLogListener getObject() {
				return listeners[0];
			}

			@Override
			public ErrorLogListener getIfAvailable() {
				return listeners.length == 0 ? null : listeners[0];
			}

			@Override
			public ErrorLogListener getIfUnique() {
				return getIfAvailable();
			}

			@Override
			public Stream<ErrorLogListener> orderedStream() {
				return Stream.of(listeners);
			}
		};
	}

	@Test
	@DisplayName("业务异常按自带状态码与消息返回，HTTP 仍为 200")
	void serviceException() {
		R<Void> r = handler.handleService(new ServiceException("库存不足"));

		assertThat(r.getCode()).isEqualTo(ResultCode.FAILURE.getCode());
		assertThat(r.getMsg()).isEqualTo("库存不足");
		assertThat(r.isSuccess()).isFalse();
	}

	@Test
	@DisplayName("业务异常携带指定 ResultCode 时按该码返回")
	void serviceExceptionWithResultCode() {
		R<Void> r = handler.handleService(new ServiceException(ResultCode.NOT_FOUND));

		assertThat(r.getCode()).isEqualTo(404);
		assertThat(r.getMsg()).isEqualTo(ResultCode.NOT_FOUND.getMsg());
	}

	@Test
	@DisplayName("未登录 → 401")
	void notLogin() {
		ResponseEntity<R<Void>> resp = handler.handleNotLogin(
			NotLoginException.newInstance("login", NotLoginException.NOT_TOKEN, NotLoginException.NOT_TOKEN_MESSAGE, null));

		assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		assertThat(resp.getBody().getCode()).isEqualTo(401);
	}

	@Test
	@DisplayName("访问受限 → 403 且保留具体提示语")
	void forbiddenKeepsMessage() {
		ResponseEntity<R<Void>> resp = handler.handleForbidden(new ForbiddenException("当前套餐不含该功能"));

		assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(resp.getBody().getMsg()).isEqualTo("当前套餐不含该功能");
	}

	@Test
	@DisplayName("无权限 → 403 使用通用文案")
	void noPermission() {
		ResponseEntity<R<Void>> resp = handler.handleNoPermission(new NotPermissionException("system:user:add"));

		assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(resp.getBody().getMsg()).isEqualTo(ResultCode.FORBIDDEN.getMsg());
	}

	@Test
	@DisplayName("静态资源未命中 → 404，且不写错误日志（防扫描器刷日志）")
	void noResourceFound() {
		ResponseEntity<R<Void>> resp = handler.handleNoResource(
			new NoResourceFoundException(org.springframework.http.HttpMethod.GET, "/.env"));

		assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(captured).isEmpty();
	}

	@Test
	@DisplayName("缺参与请求体不可读 → 400，且不写错误日志")
	void badRequest() {
		ResponseEntity<R<Void>> missing = handler.handleBadRequest(
			new MissingServletRequestParameterException("id", "Long"));
		ResponseEntity<R<Void>> unreadable = handler.handleBadRequest(
			new HttpMessageNotReadableException("坏 JSON", (org.springframework.http.HttpInputMessage) null));

		assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(unreadable.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(captured).isEmpty();
	}

	@Test
	@DisplayName("方法不允许 → 405 并给出明确文案")
	void methodNotSupported() {
		ResponseEntity<R<Void>> resp = handler.handleMethodNotSupported(
			new HttpRequestMethodNotSupportedException("PUT"));

		assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
		assertThat(resp.getBody().getMsg()).isEqualTo("请求方法不允许");
	}

	@Test
	@DisplayName("兜底异常 → 500 并发布错误日志")
	void fallbackPublishesErrorLog() {
		RuntimeException boom = new RuntimeException("空指针替身");

		ResponseEntity<R<Void>> resp = handler.handleException(boom, mock(HttpServletRequest.class));

		assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
		assertThat(resp.getBody().getCode()).isEqualTo(500);
		assertThat(captured).containsExactly(boom);
	}

	@Test
	@DisplayName("错误日志监听器自身抛异常不影响 500 响应")
	void listenerFailureDoesNotBreakResponse() {
		ErrorLogListener broken = (request, e) -> {
			throw new IllegalStateException("落库失败");
		};
		GlobalExceptionHandler h = new GlobalExceptionHandler(providerOf(broken));

		ResponseEntity<R<Void>> resp = h.handleException(new RuntimeException("boom"), mock(HttpServletRequest.class));

		assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
	}
}
