package com.mugsun.core.web.handler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 走真实的 Spring MVC 异常解析链：控制器抛出 DuplicateKeyException 时，
 * 由 GlobalExceptionHandler 的兜底方法识别并返回 HTTP 400，响应体不带 SQL 与约束名。
 */
class DuplicateKeyMvcTest {

	private MockMvc mvc;
	private final List<Throwable> captured = new ArrayList<>();

	@RestController
	static class DuplicateController {
		@PostMapping("/dup")
		public String dup() {
			throw new DuplicateKeyException(
				"ERROR: duplicate key value violates unique constraint \"uk_sys_user_username\" "
					+ "Detail: Key (username)=(alice) already exists. SQL: INSERT INTO sys_user ...");
		}

		@PostMapping("/boom")
		public String boom() {
			throw new IllegalStateException("boom");
		}
	}

	@BeforeEach
	void setUp() {
		ErrorLogListener listener = (request, e) -> captured.add(e);
		@SuppressWarnings("unchecked")
		ObjectProvider<ErrorLogListener> provider = mock(ObjectProvider.class);
		when(provider.orderedStream()).thenAnswer(inv -> Stream.of(listener));
		mvc = MockMvcBuilders.standaloneSetup(new DuplicateController())
			.setControllerAdvice(new GlobalExceptionHandler(provider))
			.build();
	}

	@Test
	@DisplayName("控制器抛 DuplicateKeyException → HTTP 400，code=400，固定文案，不进错误日志")
	void duplicateKeyReturns400() throws Exception {
		MvcResult result = mvc.perform(post("/dup"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value(400))
			.andExpect(jsonPath("$.msg").value("数据已存在，请勿重复提交"))
			.andReturn();

		String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
		assertThat(body).doesNotContain("uk_sys_user_username", "sys_user", "INSERT", "alice", "duplicate");
		assertThat(captured).isEmpty();
	}

	@Test
	@DisplayName("其它未捕获异常仍是 HTTP 500 并进错误日志")
	void otherExceptionStill500() throws Exception {
		mvc.perform(post("/boom")).andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value(500));

		assertThat(captured).hasSize(1).first().isInstanceOf(IllegalStateException.class);
	}
}
