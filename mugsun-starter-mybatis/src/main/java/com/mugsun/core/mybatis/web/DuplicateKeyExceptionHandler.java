package com.mugsun.core.mybatis.web;

import com.mugsun.core.tool.api.R;
import com.mugsun.core.tool.api.ResultCode;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.sql.SQLException;

/**
 * 唯一约束冲突 → HTTP 400「数据已存在，请勿重复提交」。
 * <p>撞上数据库唯一索引按客户端重复提交处理：响应只给固定文案，不带 SQL、约束名和字段值；
 * 不经过 starter-web 的兜底 500，所以也不发布 {@code ErrorLogListener}（不进错误日志表）。
 * <p>放在 starter-mybatis 而不是 starter-web：{@link DuplicateKeyException} 在 spring-tx 里，
 * starter-web 不依赖它；用到数据库的应用才会引本模块。
 * <p>{@link Ordered#HIGHEST_PRECEDENCE}：Spring 按 Advice 的顺序找第一个能处理的方法，
 * 不排序的 Advice（如 GlobalExceptionHandler）是最低优先级，它的 {@code @ExceptionHandler(Exception.class)} 不会先接走。
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class DuplicateKeyExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(DuplicateKeyExceptionHandler.class);

	/** 返回给客户端的固定文案 */
	public static final String MESSAGE = "数据已存在，请勿重复提交";

	@ExceptionHandler(DuplicateKeyException.class)
	public ResponseEntity<R<Void>> handleDuplicateKey(DuplicateKeyException e, HttpServletRequest request) {
		Throwable root = rootCause(e);
		SQLException sql = firstSqlException(e);
		// 只记类名、SQLState 和厂商错误码；异常 message 里有 SQL 和字段值，只在 DEBUG 级别带栈输出
		log.warn("唯一约束冲突，按 400 返回：uri={}，异常={}，根因={}，SQLState={}，厂商错误码={}",
			request != null ? request.getRequestURI() : null, e.getClass().getName(), root.getClass().getName(),
			sql != null ? sql.getSQLState() : null, sql != null ? sql.getErrorCode() : null);
		log.debug("唯一约束冲突详情", e);
		return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(R.fail(ResultCode.FAILURE, MESSAGE));
	}

	static Throwable rootCause(Throwable e) {
		Throwable cur = e;
		for (int i = 0; i < 32 && cur.getCause() != null && cur.getCause() != cur; i++) {
			cur = cur.getCause();
		}
		return cur;
	}

	static SQLException firstSqlException(Throwable e) {
		Throwable cur = e;
		for (int i = 0; i < 32 && cur != null; i++) {
			if (cur instanceof SQLException sql) {
				return sql;
			}
			cur = cur.getCause();
		}
		return null;
	}
}
