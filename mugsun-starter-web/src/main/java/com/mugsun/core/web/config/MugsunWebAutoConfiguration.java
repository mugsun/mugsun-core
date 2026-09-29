package com.mugsun.core.web.config;

import cn.dev33.satoken.interceptor.SaInterceptor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mugsun.core.web.crypto.ApiCryptoService;
import com.mugsun.core.web.crypto.DecryptRequestAdvice;
import com.mugsun.core.web.crypto.EncryptResponseAdvice;
import com.mugsun.core.web.handler.ErrorLogListener;
import com.mugsun.core.web.handler.GlobalExceptionHandler;
import com.mugsun.core.web.jackson.SafeNumberModule;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 基座自动装配：注册全局异常处理、大整数序列化模块、Sa-Token 注解鉴权拦截器与接口加解密 Advice
 */
@AutoConfiguration
public class MugsunWebAutoConfiguration implements WebMvcConfigurer {

	@Bean
	@org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
	public GlobalExceptionHandler globalExceptionHandler(ObjectProvider<ErrorLogListener> errorLogListeners) {
		return new GlobalExceptionHandler(errorLogListeners);
	}

	@Bean
	@org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
	public SafeNumberModule safeNumberModule() {
		return new SafeNumberModule();
	}

	@Bean
	@org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
	public ApiCryptoService apiCryptoService() {
		return new ApiCryptoService();
	}

	@Bean
	@org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
	public DecryptRequestAdvice decryptRequestAdvice(ApiCryptoService apiCryptoService, ObjectMapper objectMapper) {
		return new DecryptRequestAdvice(apiCryptoService, objectMapper);
	}

	@Bean
	@org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
	public EncryptResponseAdvice encryptResponseAdvice(ApiCryptoService apiCryptoService, ObjectMapper objectMapper) {
		return new EncryptResponseAdvice(apiCryptoService, objectMapper);
	}

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		// 开启 Sa-Token 注解鉴权（@SaCheckLogin / @SaCheckPermission 等）
		// SSE/DeferredResult 完成后 Tomcat ASYNC 回派时无 Sa-Token 请求上下文，跳过以免打断已提交的流
		SaInterceptor sa = new SaInterceptor();
		registry.addInterceptor(new HandlerInterceptor() {
			@Override
			public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
				throws Exception {
				if (request.getDispatcherType() == DispatcherType.ASYNC) {
					return true;
				}
				return sa.preHandle(request, response, handler);
			}
		}).addPathPatterns("/**");
	}
}
