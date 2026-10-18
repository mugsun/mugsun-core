package com.mugsun.core.mybatis.config;

import com.mugsun.core.mybatis.web.DuplicateKeyExceptionHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.web.servlet.DispatcherServlet;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 自动装配的注册与装配条件：已写进 AutoConfiguration.imports；
 * 只在 Servlet Web 且有 spring-webmvc 时装配 {@link DuplicateKeyExceptionHandler}。
 */
class MugsunMybatisWebAutoConfigurationTest {

	@Test
	@DisplayName("MugsunMybatisWebAutoConfiguration 已登记在 AutoConfiguration.imports")
	void registeredInImports() {
		List<String> candidates = new ArrayList<>();
		ImportCandidates.load(AutoConfiguration.class, getClass().getClassLoader()).forEach(candidates::add);
		assertThat(candidates).contains(MugsunMybatisWebAutoConfiguration.class.getName());
	}

	@Test
	@DisplayName("Servlet Web 应用装配 DuplicateKeyExceptionHandler")
	void registeredInServletApp() {
		new WebApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(MugsunMybatisWebAutoConfiguration.class))
			.run(context -> assertThat(context).hasSingleBean(DuplicateKeyExceptionHandler.class));
	}

	@Test
	@DisplayName("classpath 上没有 spring-webmvc（DispatcherServlet）时不装配")
	void notRegisteredWithoutWebMvc() {
		new WebApplicationContextRunner()
			.withClassLoader(new FilteredClassLoader(DispatcherServlet.class))
			.withConfiguration(AutoConfigurations.of(MugsunMybatisWebAutoConfiguration.class))
			.run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(DuplicateKeyExceptionHandler.class));
	}

	@Test
	@DisplayName("Reactive（非 Servlet）Web 应用不装配")
	void notRegisteredInReactiveApp() {
		new ReactiveWebApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(MugsunMybatisWebAutoConfiguration.class))
			.run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(DuplicateKeyExceptionHandler.class));
	}
}
