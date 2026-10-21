package com.mugsun.core.mybatis.web;

import com.mugsun.core.mybatis.config.MugsunMybatisWebAutoConfiguration;
import com.mugsun.core.web.config.MugsunWebAutoConfiguration;
import com.mugsun.core.web.handler.ErrorLogListener;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 与 starter-web 的 GlobalExceptionHandler（兜底 Exception → 500 并发布错误日志）一起按自动装配加载，
 * 走真实的 Spring MVC 异常解析链，验证唯一约束冲突由本模块处理成 400。
 */
class DuplicateKeyPrecedenceTest {

	private static final String[] EPHEMERAL_KEYS = {
		"mugsun.crypto.allow-ephemeral-keys=true"};

	private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(DispatcherServletAutoConfiguration.class, WebMvcAutoConfiguration.class,
			HttpMessageConvertersAutoConfiguration.class, JacksonAutoConfiguration.class,
			MugsunWebAutoConfiguration.class, MugsunMybatisWebAutoConfiguration.class))
		// ApiCryptoService 缺密钥默认拒绝启动；本测试与接口加密无关，用进程内临时密钥
		.withPropertyValues(EPHEMERAL_KEYS)
		.withUserConfiguration(TestConfig.class);

	@Configuration(proxyBeanMethods = false)
	static class TestConfig {
		@Bean
		CapturingListener capturingListener() {
			return new CapturingListener();
		}

		@Bean
		ThrowingController throwingController() {
			return new ThrowingController();
		}
	}

	static class CapturingListener implements ErrorLogListener {
		final List<Throwable> captured = new CopyOnWriteArrayList<>();

		@Override
		public void onError(jakarta.servlet.http.HttpServletRequest request, Throwable error) {
			captured.add(error);
		}
	}

	@RestController
	static class ThrowingController {
		@PostMapping("/dup")
		public String dup() {
			throw new DuplicateKeyException("duplicate key value violates unique constraint \"uk_sys_user_username\" "
				+ "Key (username)=(alice) already exists. SQL: INSERT INTO sys_user ...");
		}

		@PostMapping("/integrity")
		public String integrity() {
			throw new DataIntegrityViolationException("null value in column \"name\"");
		}

		@PostMapping("/boom")
		public String boom() {
			throw new IllegalStateException("boom");
		}
	}

	private static MockMvc mvc(WebApplicationContext context) {
		return MockMvcBuilders.webAppContextSetup(context).build();
	}

	@Test
	@DisplayName("两个 Advice 同时在场：DuplicateKeyException → 400 固定文案，不进错误日志监听")
	void duplicateKeyWinsOverGlobalHandler() {
		runner.run(context -> {
			assertThat(context).hasSingleBean(DuplicateKeyExceptionHandler.class)
				.hasSingleBean(com.mugsun.core.web.handler.GlobalExceptionHandler.class);

			String body = mvc(context).perform(post("/dup"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value(400))
				.andExpect(jsonPath("$.msg").value(DuplicateKeyExceptionHandler.MESSAGE))
				.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

			assertThat(body).doesNotContain("uk_sys_user_username", "sys_user", "INSERT", "alice", "duplicate");
			assertThat(context.getBean(CapturingListener.class).captured).isEmpty();
		});
	}

	/** 应用自己声明的 GlobalExceptionHandler：用户配置先于自动装配注册，Advice 注册顺序排在本模块前面 */
	@Configuration(proxyBeanMethods = false)
	static class UserGlobalHandlerConfig {
		@Bean
		com.mugsun.core.web.handler.GlobalExceptionHandler globalExceptionHandler(
				org.springframework.beans.factory.ObjectProvider<ErrorLogListener> listeners) {
			return new com.mugsun.core.web.handler.GlobalExceptionHandler(listeners);
		}
	}

	@Test
	@DisplayName("GlobalExceptionHandler 先注册（应用自定义）时，靠 @Order(HIGHEST_PRECEDENCE) 仍由本模块处理成 400")
	void duplicateKeyWinsEvenWhenGlobalHandlerRegisteredFirst() {
		new WebApplicationContextRunner()
			.withUserConfiguration(UserGlobalHandlerConfig.class, TestConfig.class)
			.withConfiguration(AutoConfigurations.of(DispatcherServletAutoConfiguration.class, WebMvcAutoConfiguration.class,
				HttpMessageConvertersAutoConfiguration.class, JacksonAutoConfiguration.class,
				MugsunWebAutoConfiguration.class, MugsunMybatisWebAutoConfiguration.class))
			.withPropertyValues(EPHEMERAL_KEYS)
			.run(context -> {
				String[] names = context.getBeanNamesForType(Object.class);
				int global = java.util.Arrays.asList(names).indexOf("globalExceptionHandler");
				int dup = java.util.Arrays.asList(names).indexOf("duplicateKeyExceptionHandler");
				assertThat(global).isNotNegative().isLessThan(dup);

				mvc(context).perform(post("/dup"))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.msg").value(DuplicateKeyExceptionHandler.MESSAGE));
				assertThat(context.getBean(CapturingListener.class).captured).isEmpty();
			});
	}

	@Test
	@DisplayName("其它完整性异常与未捕获异常仍由 GlobalExceptionHandler 处理成 500 并进错误日志监听")
	void otherExceptionsStill500() {
		runner.run(context -> {
			MockMvc mvc = mvc(context);
			mvc.perform(post("/integrity")).andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value(500));
			mvc.perform(post("/boom")).andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value(500));

			assertThat(context.getBean(CapturingListener.class).captured)
				.hasSize(2)
				.hasExactlyElementsOfTypes(DataIntegrityViolationException.class, IllegalStateException.class);
		});
	}

	@Test
	@DisplayName("非 Web 应用不装配 DuplicateKeyExceptionHandler")
	void notRegisteredOutsideWeb() {
		new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(MugsunMybatisWebAutoConfiguration.class))
			.run(context -> assertThat(context).doesNotHaveBean(DuplicateKeyExceptionHandler.class));
	}
}
