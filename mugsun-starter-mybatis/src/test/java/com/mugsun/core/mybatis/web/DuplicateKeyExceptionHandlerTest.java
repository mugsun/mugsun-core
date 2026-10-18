package com.mugsun.core.mybatis.web;

import com.mugsun.core.tool.api.R;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class DuplicateKeyExceptionHandlerTest {

	/** 模拟 PostgreSQL 驱动经 MyBatis / Spring 翻译后的异常：message 里有 SQL、约束名和字段值 */
	private static DuplicateKeyException pgDuplicate() {
		SQLException sql = new SQLException(
			"ERROR: duplicate key value violates unique constraint \"uk_sys_user_username\"\n"
				+ "  Detail: Key (tenant_id, username)=(000000, alice) already exists.", "23505", 0);
		return new DuplicateKeyException(
			"\n### Error updating database.  Cause: " + sql.getMessage()
				+ "\n### SQL: INSERT INTO sys_user (id, tenant_id, username) VALUES (?, ?, ?)", sql);
	}

	@Test
	@DisplayName("唯一约束冲突 → HTTP 400，code=400，固定文案，响应不带 SQL、约束名和字段值")
	void returns400WithoutLeak() {
		ResponseEntity<R<Void>> resp = new DuplicateKeyExceptionHandler()
			.handleDuplicateKey(pgDuplicate(), new MockHttpServletRequest("POST", "/system/user"));

		assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(resp.getBody().getCode()).isEqualTo(400);
		assertThat(resp.getBody().isSuccess()).isFalse();
		assertThat(resp.getBody().getMsg()).isEqualTo("数据已存在，请勿重复提交");
		assertThat(resp.getBody().getData()).isNull();
	}

	@Test
	@DisplayName("WARN 日志记根因类名、SQLState 和厂商错误码，不记 SQL、约束名和字段值")
	void warnLogHasCodesButNoValues(CapturedOutput output) {
		SQLException dm = new SQLException("违反表[SYS_USER]唯一性约束: alice", "23000", -6602);
		new DuplicateKeyExceptionHandler()
			.handleDuplicateKey(new DuplicateKeyException("dup: alice", dm), new MockHttpServletRequest("POST", "/x"));

		String warn = output.getOut().lines().filter(l -> l.contains("唯一约束冲突，按 400 返回")).findFirst().orElseThrow();
		assertThat(warn).contains("WARN", "uri=/x", "根因=java.sql.SQLException", "SQLState=23000", "厂商错误码=-6602");
		assertThat(warn).doesNotContain("alice", "SYS_USER");
	}

	@Test
	@DisplayName("没有 SQLException 根因时日志里 SQLState/厂商错误码记为 null，不抛异常")
	void noSqlCause(CapturedOutput output) {
		ResponseEntity<R<Void>> resp = new DuplicateKeyExceptionHandler()
			.handleDuplicateKey(new DuplicateKeyException("dup"), null);

		assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(output.getOut()).contains("SQLState=null", "厂商错误码=null");
	}
}
