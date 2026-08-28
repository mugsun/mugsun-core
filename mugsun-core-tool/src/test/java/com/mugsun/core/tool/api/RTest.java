package com.mugsun.core.tool.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 统一响应结构 R 的语义约束：前端只认 success 与 code，构造入口不得跑偏。
 */
class RTest {

	@Test
	@DisplayName("data 返回成功码与成功文案，并携带数据")
	void dataCarriesPayloadWithSuccessCode() {
		R<String> r = R.data("payload");

		assertThat(r.getCode()).isEqualTo(ResultCode.SUCCESS.getCode());
		assertThat(r.isSuccess()).isTrue();
		assertThat(r.getData()).isEqualTo("payload");
		assertThat(r.getMsg()).isEqualTo(ResultCode.SUCCESS.getMsg());
	}

	@Test
	@DisplayName("data 允许 null 数据，仍是成功响应")
	void dataAcceptsNull() {
		R<String> r = R.data(null);

		assertThat(r.isSuccess()).isTrue();
		assertThat(r.getData()).isNull();
	}

	@Test
	@DisplayName("success(msg) 覆盖文案但保持成功码，无数据")
	void successOverridesMessage() {
		R<Void> r = R.success("导入完成");

		assertThat(r.getCode()).isEqualTo(ResultCode.SUCCESS.getCode());
		assertThat(r.isSuccess()).isTrue();
		assertThat(r.getMsg()).isEqualTo("导入完成");
		assertThat(r.getData()).isNull();
	}

	@Test
	@DisplayName("fail(msg) 使用 FAILURE 码且 success 为 false")
	void failUsesFailureCode() {
		R<Void> r = R.fail("参数不合法");

		assertThat(r.getCode()).isEqualTo(ResultCode.FAILURE.getCode());
		assertThat(r.isSuccess()).isFalse();
		assertThat(r.getMsg()).isEqualTo("参数不合法");
	}

	@Test
	@DisplayName("fail(ResultCode) 同时取枚举的码与文案")
	void failFromResultCode() {
		R<Void> r = R.fail(ResultCode.UNAUTHORIZED);

		assertThat(r.getCode()).isEqualTo(401);
		assertThat(r.getMsg()).isEqualTo(ResultCode.UNAUTHORIZED.getMsg());
		assertThat(r.isSuccess()).isFalse();
	}

	@Test
	@DisplayName("fail(ResultCode, msg) 保留枚举码但替换文案")
	void failFromResultCodeWithCustomMessage() {
		R<Void> r = R.fail(ResultCode.FORBIDDEN, "无该菜单权限");

		assertThat(r.getCode()).isEqualTo(403);
		assertThat(r.getMsg()).isEqualTo("无该菜单权限");
		assertThat(r.isSuccess()).isFalse();
	}

	@Test
	@DisplayName("dataType 默认为空，仅接口加密场景显式置为 ENCRYPT")
	void dataTypeDefaultsToNull() {
		R<String> r = R.data("plain");
		assertThat(r.getDataType()).isNull();

		r.setDataType("ENCRYPT");
		assertThat(r.getDataType()).isEqualTo("ENCRYPT");
	}

	@Test
	@DisplayName("状态码枚举取值与 HTTP 语义一致")
	void resultCodeValues() {
		assertThat(ResultCode.SUCCESS.getCode()).isEqualTo(200);
		assertThat(ResultCode.FAILURE.getCode()).isEqualTo(400);
		assertThat(ResultCode.NOT_FOUND.getCode()).isEqualTo(404);
		assertThat(ResultCode.SERVER_ERROR.getCode()).isEqualTo(500);
	}
}
