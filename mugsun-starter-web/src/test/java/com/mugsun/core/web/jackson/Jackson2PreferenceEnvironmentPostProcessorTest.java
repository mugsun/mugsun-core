package com.mugsun.core.web.jackson;

import com.mugsun.core.web.config.MugsunWebAutoConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.jackson2.autoconfigure.Jackson2AutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 过渡期的 Jackson 2 默认值：真实启动时要生效、应用可以覆盖，并且确实决定了响应体由哪个 JSON 库写出。
 */
class Jackson2PreferenceEnvironmentPostProcessorTest {

	private static final String CONVERTERS = "spring.http.converters.preferred-json-mapper";
	private static final String CODECS = "spring.http.codecs.preferred-json-mapper";
	private static final String WEBSOCKET = "spring.websocket.messaging.preferred-json-mapper";

	/** 超出 JS 安全整数范围的雪花主键 */
	private static final long SNOWFLAKE_ID = 1234567890123456789L;

	@Configuration(proxyBeanMethods = false)
	static class EmptyApp {
	}

	private static SpringApplicationBuilder app() {
		return new SpringApplicationBuilder(EmptyApp.class)
			.web(WebApplicationType.NONE).bannerMode(Banner.Mode.OFF).logStartupInfo(false);
	}

	@Test
	@DisplayName("应用真实启动：三个配置项默认都是 jackson2（靠 spring.factories 登记生效）")
	void defaultsApplyOnRealStartup() {
		try (ConfigurableApplicationContext context = app().run()) {
			assertThat(context.getEnvironment().getProperty(CONVERTERS)).isEqualTo("jackson2");
			assertThat(context.getEnvironment().getProperty(CODECS)).isEqualTo("jackson2");
			assertThat(context.getEnvironment().getProperty(WEBSOCKET)).isEqualTo("jackson2");
		}
	}

	@Test
	@DisplayName("应用自己配了同名项：以应用的为准，没配的仍用默认值")
	void applicationValueWins() {
		try (ConfigurableApplicationContext context = app().run("--" + CONVERTERS + "=jackson")) {
			assertThat(context.getEnvironment().getProperty(CONVERTERS)).isEqualTo("jackson");
			assertThat(context.getEnvironment().getProperty(CODECS)).isEqualTo("jackson2");
		}
	}

	/** 和真实应用一样：Jackson 3 和 Jackson 2 的自动装配都在，由配置项决定用哪个写响应体 */
	private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(DispatcherServletAutoConfiguration.class, WebMvcAutoConfiguration.class,
			HttpMessageConvertersAutoConfiguration.class, JacksonAutoConfiguration.class, Jackson2AutoConfiguration.class,
			MugsunWebAutoConfiguration.class))
		// 没配 api-key 时会自动生成密钥文件，mugsun.home 指到临时目录，不碰 ~/.mugsun
		.withPropertyValues("mugsun.home=" + System.getProperty("java.io.tmpdir") + "/mugsun-test-home-"
			+ ProcessHandle.current().pid())
		.withUserConfiguration(TestConfig.class);

	@Configuration(proxyBeanMethods = false)
	static class TestConfig {
		@Bean
		IdController idController() {
			return new IdController();
		}
	}

	record IdView(Long id, Long total) {
	}

	@RestController
	static class IdController {
		@GetMapping("/id")
		public IdView id() {
			return new IdView(SNOWFLAKE_ID, 42L);
		}
	}

	private static String body(WebApplicationContext context) throws Exception {
		return MockMvcBuilders.webAppContextSetup(context).build().perform(get("/id"))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
	}

	@Test
	@DisplayName("加了默认值：响应体由 Jackson 2 写出，超出安全范围的 Long 转成字符串，范围内的仍是数字")
	void bigLongWrittenAsStringWithDefaults() {
		runner.withInitializer(context -> new Jackson2PreferenceEnvironmentPostProcessor()
				.postProcessEnvironment(context.getEnvironment(), new SpringApplication()))
			.run(context -> assertThat(body(context))
				.contains("\"id\":\"" + SNOWFLAKE_ID + "\"")
				.contains("\"total\":42"));
	}

	@Test
	@DisplayName("不加默认值：Spring Boot 4 用 Jackson 3 写响应体，Long 原样是数字（这个类要防的就是这种情况）")
	void bigLongStaysNumberWithoutDefaults() {
		runner.run(context -> assertThat(body(context)).contains("\"id\":" + SNOWFLAKE_ID));
	}
}
