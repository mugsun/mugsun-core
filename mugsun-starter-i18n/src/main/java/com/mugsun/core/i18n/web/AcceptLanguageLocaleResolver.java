package com.mugsun.core.i18n.web;

import com.mugsun.core.i18n.locale.LocaleNegotiator;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.web.servlet.LocaleResolver;

import java.util.Locale;

/**
 * 这次请求用哪种语言：看请求头 {@code Accept-Language}，交给 {@link LocaleNegotiator} 定。
 *
 * <p>和 Spring 自带的做法有两点不同：没带请求头时用配置的默认语言，不跟着服务器操作系统的语言走；
 * 请求的语言不支持时也用默认语言，所以结果一定是支持清单里的一个。
 */
public class AcceptLanguageLocaleResolver implements LocaleResolver {

	/** 同一个请求里只算一次，结果记在请求上 */
	private static final String ATTRIBUTE = AcceptLanguageLocaleResolver.class.getName() + ".locale";

	private final LocaleNegotiator negotiator;

	public AcceptLanguageLocaleResolver(LocaleNegotiator negotiator) {
		this.negotiator = negotiator;
	}

	@Override
	public Locale resolveLocale(HttpServletRequest request) {
		if (request.getAttribute(ATTRIBUTE) instanceof Locale resolved) {
			return resolved;
		}
		Locale locale = negotiator.negotiate(request.getHeader(HttpHeaders.ACCEPT_LANGUAGE));
		request.setAttribute(ATTRIBUTE, locale);
		return locale;
	}

	@Override
	public void setLocale(HttpServletRequest request, HttpServletResponse response, Locale locale) {
		throw new UnsupportedOperationException("语言由请求头 Accept-Language 决定，不能在服务端改；"
			+ "想用别的办法定语言（比如记在会话里），自己注册一个名为 localeResolver 的 Bean");
	}
}
