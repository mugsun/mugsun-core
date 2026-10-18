package com.mugsun.core.mybatis.config;

import com.mugsun.core.mybatis.web.DuplicateKeyExceptionHandler;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.DispatcherServlet;

/**
 * 持久层 × Servlet Web：注册唯一约束冲突 → 400 的异常处理。非 Servlet Web 应用（无 spring-webmvc）不装配。
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(DispatcherServlet.class)
public class MugsunMybatisWebAutoConfiguration {

	@Bean
	@ConditionalOnMissingBean
	public DuplicateKeyExceptionHandler duplicateKeyExceptionHandler() {
		return new DuplicateKeyExceptionHandler();
	}
}
