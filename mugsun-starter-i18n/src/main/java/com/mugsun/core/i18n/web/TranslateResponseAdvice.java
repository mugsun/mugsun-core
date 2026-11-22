package com.mugsun.core.i18n.web;

import com.mugsun.core.i18n.message.MessageTranslator;
import com.mugsun.core.tool.api.R;
import org.springframework.core.MethodParameter;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * 返回前把统一返回体 {@link R} 里的提示语（{@code msg}）翻译成这次请求的语言。
 * 控制器直接返回的、异常处理器转出来的都会经过这里，所以业务代码里写死的中文提示不用改：
 * 翻译文件里有这句的翻译就换掉，没有就原样返回。规则见 {@link MessageTranslator#translate(String)}。
 *
 * <p>只动 {@code msg}，不动 {@code data}。不是 {@link R} 的返回值（文件下载、流式输出等）不处理。
 */
@ControllerAdvice
@Order(TranslateResponseAdvice.ORDER)
public class TranslateResponseAdvice implements ResponseBodyAdvice<Object> {

	/** 排在前面：先把提示语翻译好，再轮到别的处理（比如响应加密） */
	public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 1000;

	private final MessageTranslator translator;

	public TranslateResponseAdvice(MessageTranslator translator) {
		this.translator = translator;
	}

	@Override
	public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
		return true;
	}

	@Override
	public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType,
			Class<? extends HttpMessageConverter<?>> selectedConverterType, ServerHttpRequest request,
			ServerHttpResponse response) {
		if (!(body instanceof R<?> result)) {
			return body;
		}
		String msg = result.getMsg();
		if (msg == null || msg.isEmpty()) {
			return body;
		}
		String translated = translator.translate(msg);
		if (msg.equals(translated)) {
			return body;
		}
		return withMsg(result, translated);
	}

	/**
	 * 换提示语时复制一份再改，不动原来的对象：原对象可能被缓存着或者是共用的常量，
	 * 直接改会让之后的中文请求也拿到英文。{@link R} 的子类没法通用地复制，只好就地改。
	 */
	private static <T> R<T> withMsg(R<T> source, String msg) {
		if (source.getClass() != R.class) {
			source.setMsg(msg);
			return source;
		}
		R<T> copy = new R<>();
		copy.setCode(source.getCode());
		copy.setSuccess(source.isSuccess());
		copy.setData(source.getData());
		copy.setMsg(msg);
		copy.setDataType(source.getDataType());
		return copy;
	}
}
