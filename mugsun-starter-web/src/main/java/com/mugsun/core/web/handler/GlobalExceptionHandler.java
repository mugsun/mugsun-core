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

	/** 客户端参数错误（类型不匹配/缺参/请求体不可读）→ 400：客户端 4xx 非服务器故障，不入错误日志 */
	@ExceptionHandler({org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
		org.springframework.web.bind.MissingServletRequestParameterException.class,
		org.springframework.http.converter.HttpMessageNotReadableException.class})
	public ResponseEntity<R<Void>> handleBadRequest(Exception e) {
		Throwable cur = e;
		while (cur != null) {
			if (cur instanceof ServiceException service) {
				return ResponseEntity.ok(R.fail(service.getResultCode(), service.getMessage()));
			}
			cur = cur.getCause();
		}
		return ResponseEntity.badRequest().body(R.fail(ResultCode.FAILURE));
	}

	/** 请求方法不允许 → 405 */
	@ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
	public ResponseEntity<R<Void>> handleMethodNotSupported(
			org.springframework.web.HttpRequestMethodNotSupportedException e) {
		return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
			.body(R.fail(ResultCode.FAILURE, "请求方法不允许"));
	}

	/** 唯一约束冲突的异常类名（spring-tx）。本模块不依赖 spring-tx，按类名识别，见 {@link #isDuplicateKey(Throwable)} */
	static final String DUPLICATE_KEY_EXCEPTION = "org.springframework.dao.DuplicateKeyException";

	/** 唯一约束冲突返回给客户端的固定文案：不带 SQL、约束名、字段值 */
	static final String DUPLICATE_KEY_MESSAGE = "数据已存在，请勿重复提交";

	/**
	 * 唯一约束冲突 → 400「数据已存在」。业务侧没提前查重、撞上数据库唯一索引时，这是客户端重复提交，不是服务器故障：
	 * 不进错误日志监听，响应只给固定文案（异常 message 里有 SQL、约束名和字段值，只写服务端日志）。
	 */
	ResponseEntity<R<Void>> handleDuplicateKey(Exception e, HttpServletRequest request) {
		String uri = request != null ? request.getRequestURI() : null;
		log.warn("唯一约束冲突，按 400 返回：uri={}，异常={}", uri, e.getClass().getName());
		log.debug("唯一约束冲突详情", e);
		return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(R.fail(ResultCode.FAILURE, DUPLICATE_KEY_MESSAGE));
	}

	/**
	 * 是否唯一约束冲突（含子类）。spring-tx 不在本模块依赖里：直接写 {@code @ExceptionHandler(DuplicateKeyException.class)}
	 * 会编译失败（d5e25f1 即因此 CI 失败、随后被撤掉），引成必选依赖又会强加给不用数据库的应用，所以按类名沿继承链识别。
	 */
	static boolean isDuplicateKey(Throwable e) {
		for (Class<?> c = e.getClass(); c != null; c = c.getSuperclass()) {
			if (DUPLICATE_KEY_EXCEPTION.equals(c.getName())) {
				return true;
			}
		}
		return false;
	}

	/** 兜底 → 500（同步发布错误日志监听，监听器内部异步落库；监听异常不污染主响应）。唯一约束冲突除外，见上 */
	@ExceptionHandler(Exception.class)
	public ResponseEntity<R<Void>> handleException(Exception e, HttpServletRequest request) {
		if (isDuplicateKey(e)) {
			return handleDuplicateKey(e, request);
		}
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
