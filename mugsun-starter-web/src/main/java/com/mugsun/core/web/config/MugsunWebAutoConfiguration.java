package com.mugsun.core.web.config;

import cn.dev33.satoken.interceptor.SaInterceptor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mugsun.core.web.crypto.ApiCryptoService;
import com.mugsun.core.web.crypto.DecryptRequestAdvice;
import com.mugsun.core.web.crypto.EncryptResponseAdvice;
import com.mugsun.core.web.handler.GlobalExceptionHandler;
import com.mugsun.core.web.jackson.SafeNumberModule;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 基座自动装配：注册全局异常处理、大整数序列化模块、Sa-Token 注解鉴权拦截器与接口加解密 Advice
 */
@AutoConfiguration
public class MugsunWebAutoConfiguration implements WebMvcConfigurer {

	@Bean
	public GlobalExceptionHandler globalExceptionHandler() {
		return new GlobalExceptionHandler();
	}

	@Bean
	public SafeNumberModule safeNumberModule() {
		return new SafeNumberModule();
	}

	@Bean
	public ApiCryptoService apiCryptoService() {
		return new ApiCryptoService();
	}

	@Bean
	public DecryptRequestAdvice decryptRequestAdvice(ApiCryptoService apiCryptoService, ObjectMapper objectMapper) {
		return new DecryptRequestAdvice(apiCryptoService, objectMapper);
	}

	@Bean
	public EncryptResponseAdvice encryptResponseAdvice(ApiCryptoService apiCryptoService, ObjectMapper objectMapper) {
		return new EncryptResponseAdvice(apiCryptoService, objectMapper);
	}

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		// 开启 Sa-Token 注解鉴权（@SaCheckLogin / @SaCheckPermission 等）
		registry.addInterceptor(new SaInterceptor()).addPathPatterns("/**");
	}
}
