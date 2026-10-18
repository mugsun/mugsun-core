package com.mugsun.core.mybatis.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.support.SQLErrorCodeSQLExceptionTranslator;
import org.springframework.jdbc.support.SQLErrorCodesFactory;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * classpath 根目录的 sql-error-codes.xml：补上达梦的唯一键错误码，且不影响 Spring 默认表里的数据库。
 */
class SqlErrorCodesTest {

	private static DataAccessException translate(String productName, String sqlState, int vendorCode) {
		return new SQLErrorCodeSQLExceptionTranslator(productName)
			.translate("insert", "INSERT INTO t VALUES (?)", new SQLException("x", sqlState, vendorCode));
	}

	@ParameterizedTest(name = "达梦 {0} → DuplicateKeyException")
	@ValueSource(ints = {-6602, -6612, -6625})
	@DisplayName("达梦唯一键错误码翻译成 DuplicateKeyException")
	void dmDuplicateKey(int code) {
		assertThat(translate("DM DBMS", "23000", code)).isInstanceOf(DuplicateKeyException.class);
	}

	@ParameterizedTest(name = "达梦其它错误码 {0} 不算唯一键冲突")
	@ValueSource(ints = {-2106, -6609, -2601})
	void dmOtherCodes(int code) {
		assertThat(translate("DM DBMS", "23000", code)).isNotInstanceOf(DuplicateKeyException.class);
	}

	@ParameterizedTest(name = "{0} 仍按 Spring 默认表翻译")
	@CsvSource({"PostgreSQL,23505,0", "MySQL,23000,1062", "Oracle,23000,1", "H2,23505,23505"})
	@DisplayName("补充文件不替换 Spring 默认表：PostgreSQL / MySQL / Oracle / H2 的唯一键冲突仍识别")
	void defaultsStillApply(String product, String sqlState, int code) {
		assertThat(translate(product, sqlState, code)).isInstanceOf(DuplicateKeyException.class);
	}

	@org.junit.jupiter.api.Test
	@DisplayName("Spring 默认表与本模块补充表都加载了")
	void bothFilesLoaded() {
		SQLErrorCodesFactory factory = SQLErrorCodesFactory.getInstance();
		assertThat(factory.getErrorCodes("DM DBMS").getDuplicateKeyCodes()).containsExactly("-6602", "-6612", "-6625");
		assertThat(factory.getErrorCodes("PostgreSQL").getDuplicateKeyCodes()).contains("23505");
		assertThat(factory.getErrorCodes("MySQL").getDuplicateKeyCodes()).contains("1062");
	}
}
