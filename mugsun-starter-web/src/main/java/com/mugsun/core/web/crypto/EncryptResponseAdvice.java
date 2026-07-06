package com.mugsun.core.web.crypto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mugsun.core.tool.api.R;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * 响应体加密：标注 {@link ApiEncrypt} 的接口，将 R.data 序列化后 SM4 加密并置 dataType=ENCRYPT。
 */
@ControllerAdvice
public class EncryptResponseAdvice implements ResponseBodyAdvice<Object> {

	private final ApiCryptoService apiCryptoService;
	private final ObjectMapper objectMapper;

	public EncryptResponseAdvice(ApiCryptoService apiCryptoService, ObjectMapper objectMapper) {
		this.apiCryptoService = apiCryptoService;
		this.objectMapper = objectMapper;
	}

	@Override
	public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
		return returnType.hasMethodAnnotation(ApiEncrypt.class)
			|| returnType.getContainingClass().isAnnotationPresent(ApiEncrypt.class);
	}

	@Override
	public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType, Class<? extends HttpMessageConverter<?>> selectedConverterType, ServerHttpRequest request, ServerHttpResponse response) {
		if (body instanceof R<?> result && result.getData() != null) {
			try {
				String cipher = apiCryptoService.encrypt(objectMapper.writeValueAsString(result.getData()));
				@SuppressWarnings("unchecked")
				R<Object> encrypted = (R<Object>) result;
				encrypted.setData(cipher);
				encrypted.setDataType("ENCRYPT");
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		}
		return body;
	}
}
