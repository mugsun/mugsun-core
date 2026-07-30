package com.mugsun.core.web.handler;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.exception.NotRoleException;
import com.mugsun.core.tool.api.R;
import com.mugsun.core.tool.api.ResultCode;
import com.mugsun.core.tool.exception.ForbiddenException;
import com.mugsun.core.tool.exception.ServiceException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常处理：统一转为 R 响应，并对齐 HTTP 状态码
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	/** 错误日志监听（可缺省）：兜底 500 处发布，业务侧实现落库闭环 */
	private final ObjectProvider<ErrorLogListener> errorLogListeners;

	public GlobalExceptionHandler(ObjectProvider<ErrorLogListener> errorLogListeners) {
		this.errorLogListeners = errorLogListeners;
	}

	/** 业务异常 */
	@ExceptionHandler(ServiceException.class)
	public R<Void> handleService(ServiceException e) {
		return R.fail(e.getResultCode(), e.getMessage());
	}

	/** 参数校验异常 */
	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<R<Void>> handleValid(MethodArgumentNotValidException e) {
		FieldError fieldError = e.getBindingResult().getFieldError();
		String msg = fieldError != null ? fieldError.getDefaultMessage() : "参数校验失败";
		return ResponseEntity.badRequest().body(R.fail(msg));
	}

	/** 未登录 → 401 */
	@ExceptionHandler(NotLoginException.class)
	public ResponseEntity<R<Void>> handleNotLogin(NotLoginException e) {
		return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(R.fail(ResultCode.UNAUTHORIZED));
	}

	/** 访问受限（租户越权 / 套餐外功能）→ 403（携带具体提示语） */
	@ExceptionHandler(ForbiddenException.class)
	public ResponseEntity<R<Void>> handleForbidden(ForbiddenException e) {
		return ResponseEntity.status(HttpStatus.FORBIDDEN).body(R.fail(ResultCode.FORBIDDEN, e.getMessage()));
	}

	/** 无权限/无角色 → 403 */
	@ExceptionHandler({NotPermissionException.class, NotRoleException.class})
	public ResponseEntity<R<Void>> handleNoPermission(RuntimeException e) {
		return ResponseEntity.status(HttpStatus.FORBIDDEN).body(R.fail(ResultCode.FORBIDDEN));
	}

	/** 资源不存在（URL 笔误/扫描器探测走静态资源链兜底）→ 404；非系统故障，不入错误日志防噪音淹没 */
	@ExceptionHandler(NoResourceFoundException.class)
	public ResponseEntity<R<Void>> handleNoResource(NoResourceFoundException e) {
		return ResponseEntity.status(HttpStatus.NOT_FOUND).body(R.fail(ResultCode.NOT_FOUND));
	}

	/** 兜底 → 500（同步发布错误日志监听，监听器内部异步落库；监听异常不污染主响应） */
	@ExceptionHandler(Exception.class)
	public ResponseEntity<R<Void>> handleException(Exception e, HttpServletRequest request) {
		log.error("系统未捕获异常", e);
		errorLogListeners.orderedStream().forEach(listener -> {
			try {
				listener.onError(request, e);
			} catch (Exception ex) {
				log.warn("错误日志监听器执行失败：{}", ex.getMessage());
			}
		});
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(R.fail(ResultCode.SERVER_ERROR));
	}
}
