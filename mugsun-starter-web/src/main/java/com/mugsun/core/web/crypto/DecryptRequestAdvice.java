package com.mugsun.core.web.crypto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;

/**
 * 请求体解密：标注 {@link ApiDecrypt} 的接口，将 {encryptData:密文} 解密为明文 JSON 供后续绑定。
 */
@ControllerAdvice
public class DecryptRequestAdvice extends RequestBodyAdviceAdapter {

	private final ApiCryptoService apiCryptoService;
	private final ObjectMapper objectMapper;

	public DecryptRequestAdvice(ApiCryptoService apiCryptoService, ObjectMapper objectMapper) {
		this.apiCryptoService = apiCryptoService;
		this.objectMapper = objectMapper;
	}

	@Override
	public boolean supports(MethodParameter methodParameter, Type targetType, Class<? extends HttpMessageConverter<?>> converterType) {
		return methodParameter.hasMethodAnnotation(ApiDecrypt.class)
			|| methodParameter.getContainingClass().isAnnotationPresent(ApiDecrypt.class);
	}

	@Override
	public HttpInputMessage beforeBodyRead(HttpInputMessage inputMessage, MethodParameter parameter, Type targetType, Class<? extends HttpMessageConverter<?>> converterType) throws IOException {
		byte[] raw = inputMessage.getBody().readAllBytes();
		JsonNode node;
		try {
			node = objectMapper.readTree(new String(raw, StandardCharsets.UTF_8));
		} catch (Exception e) {
			// 标注了 @ApiDecrypt 即强制加密语义：非法 JSON 直接拒绝（防明文降级绕过）
			throw new com.mugsun.core.tool.exception.ServiceException("请求体须为加密报文");
		}
		JsonNode enc = node.get("encryptData");
		if (enc == null || enc.asText().isEmpty()) {
			throw new com.mugsun.core.tool.exception.ServiceException("缺少加密字段 encryptData");
		}
		try {
			byte[] plain = apiCryptoService.decrypt(enc.asText()).getBytes(StandardCharsets.UTF_8);
			return rebuild(inputMessage, plain);
		} catch (com.mugsun.core.tool.exception.ServiceException e) {
			throw e;
		} catch (Exception e) {
			throw new com.mugsun.core.tool.exception.ServiceException("加密报文解密失败");
		}
	}

	private HttpInputMessage rebuild(HttpInputMessage src, byte[] body) {
		return new HttpInputMessage() {
			@Override
			public InputStream getBody() {
				return new ByteArrayInputStream(body);
			}

			@Override
			public HttpHeaders getHeaders() {
				return src.getHeaders();
			}
		};
	}
}
