package com.mugsun.core.i18n.web;

import com.mugsun.core.i18n.locale.LocaleNegotiator;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.i18n.LocaleContext;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.LocaleResolver;

import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * 让过滤器里的代码拿到的也是这次请求的语言。
 *
 * <p>Spring MVC 要到 DispatcherServlet 里才用 {@code localeResolver} 定语言。在那之前的过滤器里
 * （登录校验、限流这类自己写响应的地方），「当前语言」要么还没有，要么是 Spring 的 RequestContextFilter
 * 直接按请求头放的：没带请求头时是服务器操作系统的语言，带了不支持的语言也照放。
 *
 * <p>这个过滤器排在前面，做两件事：
 *
 * <ol>
 * <li>用 {@code localeResolver} 把这次请求的语言定下来，放成当前语言。后面的过滤器里用
 * {@code I18n.get}、{@code I18n.translate}，结果和控制器里一样。</li>
 * <li>把请求包一层，让 {@code request.getLocale()} 返回定下来的语言。RequestContextFilter 和别的直接问请求的代码，
 * 拿到的也是它。请求头 {@code Accept-Language} 的原文不动。</li>
 * </ol>
 *
 * <p>有一处照顾不到：请求处理完往回走时，排在 RequestContextFilter 前面的过滤器里，当前语言已经被它清掉，
 * 取到的是默认语言。要在那里按请求的语言写响应，用 {@code I18n.negotiate(请求头)} 自己定语言。
 */
public class LocaleContextFilter extends OncePerRequestFilter implements Ordered {

	/** 排在设编码、还原代理转发地址这几个最靠前的过滤器后面，其余过滤器前面 */
	public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 5;

	private static final Logger log = LoggerFactory.getLogger(LocaleContextFilter.class);

	private final Supplier<LocaleResolver> resolverSupplier;
	private final LocaleNegotiator negotiator;

	private volatile LocaleResolver resolver;
	private volatile boolean resolverLookedUp;

	/**
	 * @param resolverSupplier 取 {@code localeResolver}。第一次请求时才取：过滤器在容器启动的很早阶段创建，
	 * 那时就取会把 Spring MVC 的配置连同应用的拦截器一起过早地拉起来。可以返回 null
	 * @param negotiator 没有 {@code localeResolver}、或者它在这个阶段定不出语言时，按请求头定
	 */
	public LocaleContextFilter(Supplier<LocaleResolver> resolverSupplier, LocaleNegotiator negotiator) {
		this.resolverSupplier = resolverSupplier;
		this.negotiator = negotiator;
	}

	@Override
	public int getOrder() {
		return ORDER;
	}

	/** 异步请求换线程继续处理时也要再放一次，和 RequestContextFilter 的做法一致 */
	@Override
	protected boolean shouldNotFilterAsyncDispatch() {
		return false;
	}

	/** 转到错误页的那一趟也要放，和 RequestContextFilter 的做法一致 */
	@Override
	protected boolean shouldNotFilterErrorDispatch() {
		return false;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		Locale locale = resolve(request);
		LocaleContext previous = LocaleContextHolder.getLocaleContext();
		LocaleContextHolder.setLocale(locale);
		try {
			chain.doFilter(new LocalizedRequest(request, locale), response);
		} finally {
			LocaleContextHolder.setLocaleContext(previous);
		}
	}

	private Locale resolve(HttpServletRequest request) {
		LocaleResolver current = resolver();
		if (current != null) {
			try {
				Locale locale = current.resolveLocale(request);
				if (locale != null) {
					return locale;
				}
			} catch (RuntimeException e) {
				// 应用自己定语言的办法可能要用到这时还没准备好的东西（比如登录用户）：过滤器这一段先按请求头定，
				// 到了控制器里 Spring MVC 会再问它一次
				log.debug("localeResolver 在过滤器阶段没能定出语言，这一段先按请求头定：{}", e.toString());
			}
		}
		return negotiator.negotiate(request.getHeader(HttpHeaders.ACCEPT_LANGUAGE));
	}

	private LocaleResolver resolver() {
		if (!resolverLookedUp) {
			try {
				resolver = resolverSupplier.get();
			} catch (RuntimeException e) {
				// localeResolver 建不起来时 Spring MVC 自己会报错，这里不再报一遍，只是改成按请求头定
				log.warn("取 localeResolver 失败，过滤器里的语言改成按请求头 Accept-Language 定", e);
			}
			resolverLookedUp = true;
		}
		return resolver;
	}

	/** 问请求要语言时，给定下来的那一个 */
	private static final class LocalizedRequest extends HttpServletRequestWrapper {

		private final Locale locale;

		private LocalizedRequest(HttpServletRequest request, Locale locale) {
			super(request);
			this.locale = locale;
		}

		@Override
		public Locale getLocale() {
			return locale;
		}

		@Override
		public Enumeration<Locale> getLocales() {
			return Collections.enumeration(List.of(locale));
		}
	}
}
