package com.mugsun.core.web.jackson;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 大整数序列化：雪花主键（19 位）必须以字符串出参，否则前端 JS 精度截断会导致改错行。
 * 同时不能把普通数值（总数、状态码）也变成字符串，避免前端比较逻辑失效。
 */
class SafeNumberModuleTest {

	private ObjectMapper mapper;

	/** 承载各类数值字段的出参样例 */
	static class Payload {
		public Long id;
		public long count;
		public BigInteger big;
		public Integer status;
	}

	@BeforeEach
	void setUp() {
		mapper = new ObjectMapper().registerModule(new SafeNumberModule());
	}

	private String json(Payload p) throws Exception {
		return mapper.writeValueAsString(p);
	}

	@Test
	@DisplayName("雪花级 Long（19 位）序列化为字符串")
	void snowflakeIdBecomesString() throws Exception {
		Payload p = new Payload();
		p.id = 1_957_432_109_876_543_210L;

		assertThat(json(p)).contains("\"id\":\"1957432109876543210\"");
	}

	@Test
	@DisplayName("安全范围内的 Long 仍是数字")
	void smallLongStaysNumber() throws Exception {
		Payload p = new Payload();
		p.id = 1024L;

		assertThat(json(p)).contains("\"id\":1024").doesNotContain("\"id\":\"1024\"");
	}

	@Test
	@DisplayName("2^53 边界内按数字、超界按字符串")
	void boundaryAt2Pow53() throws Exception {
		long safe = 1L << 53;
		Payload inside = new Payload();
		inside.id = safe;
		assertThat(json(inside)).contains("\"id\":9007199254740992");

		Payload outside = new Payload();
		outside.id = safe + 1;
		assertThat(json(outside)).contains("\"id\":\"9007199254740993\"");
	}

	@Test
	@DisplayName("负向超界同样转字符串")
	void negativeOutOfRangeBecomesString() throws Exception {
		Payload p = new Payload();
		p.id = -(1L << 53) - 1;

		assertThat(json(p)).contains("\"id\":\"-9007199254740993\"");
	}

	@Test
	@DisplayName("基本类型 long 与 BigInteger 同样生效")
	void primitiveLongAndBigInteger() throws Exception {
		Payload p = new Payload();
		p.count = 1_957_432_109_876_543_210L;
		p.big = new BigInteger("1957432109876543211");

		String out = json(p);
		assertThat(out).contains("\"count\":\"1957432109876543210\"");
		assertThat(out).contains("\"big\":\"1957432109876543211\"");
	}

	@Test
	@DisplayName("Integer 字段不受影响（状态码仍为数字）")
	void integerUntouched() throws Exception {
		Payload p = new Payload();
		p.status = 200;

		assertThat(json(p)).contains("\"status\":200");
	}

	@Test
	@DisplayName("null 值仍序列化为 null，不被转成字符串")
	void nullStaysNull() throws Exception {
		Payload p = new Payload();

		assertThat(json(p)).contains("\"id\":null");
	}

	@Test
	@DisplayName("字符串出参可被前端原样回传并反序列化回 Long")
	void stringIdCanBeDeserializedBack() throws Exception {
		Payload p = new Payload();
		p.id = 1_957_432_109_876_543_210L;

		Payload back = mapper.readValue(json(p), Payload.class);
		assertThat(back.id).isEqualTo(p.id);
	}
}
