package com.mugsun.core.i18n.web;

import com.mugsun.core.i18n.config.MugsunI18nAutoConfiguration;
import com.mugsun.core.i18n.locale.LocaleNegotiator;
import com.mugsun.core.i18n.message.I18n;
import com.mugsun.core.tool.api.R;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.MessageSourceAutoConfiguration;
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.jackson2.autoconfigure.Jackson2AutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.filter.RequestContextFilter;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.i18n.FixedLocaleResolver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * 模拟的请求在没带 Accept-Language 时，request.getLocale() 给的是英文，正好相当于「服务器操作系统是英文」：
 * 没有这个过滤器时，过滤器里取到的就会是英文，而控制器里是默认的中文。
 */
@DisplayName("过滤器里取到的也是这次请求的语言")
@ExtendWith(OutputCaptureExtension.class)
class LocaleContextFilterTest {

	private static final Locale ZH_CN = Locale.SIMPLIFIED_CHINESE;

	private final LocaleNegotiator negotiator = new LocaleNegotiator(ZH_CN, List.of(Locale.ENGLISH));

	/** 翻译文件在 src/test/resources 下：英文、繁体中文、日文 */
	private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(DispatcherServletAutoConfiguration.class, WebMvcAutoConfiguration.class,
			HttpMessageConvertersAutoConfiguration.class, Jackson2AutoConfiguration.class,
			MessageSourceAutoConfiguration.class, MugsunI18nAutoConfiguration.class))
		.withUserConfiguration(DemoConfig.class);

	@BeforeEach
	@AfterEach
	void resetLocale() {
		LocaleContextHolder.resetLocaleContext();
	}

	@Test
	@DisplayName("排在设编码这几个最靠前的过滤器后面、其余过滤器前面；异步请求换线程、转到错误页时也生效")
	void orderAndDispatches() {
		LocaleContextFilter filter = new LocaleContextFilter(() -> null, negotiator);

		assertThat(filter.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE + 5);
		assertThat(filter.shouldNotFilterAsyncDispatch()).isFalse();
		assertThat(filter.shouldNotFilterErrorDispatch()).isFalse();
	}

	@Test
	@DisplayName("后面的过滤器里：当前语言、问请求要到的语言都是定下来的那个；请求头的原文不动；请求结束后还原")
	void setsAndRestores() throws Exception {
		LocaleContextFilter filter = new LocaleContextFilter(() -> null, negotiator);

		assertThat(seenDownstream(filter, "en-US,en;q=0.9")).isEqualTo("en|en|[en]|en-US,en;q=0.9");
		assertThat(LocaleContextHolder.getLocaleContext()).isNull();

		// 没带请求头：请求自己说的是英文（相当于服务器操作系统的语言），定下来的是默认语言
		assertThat(seenDownstream(filter, null)).isEqualTo("zh-CN|zh-CN|[zh-CN]|null");
		// 不支持的语言：默认语言
		assertThat(seenDownstream(filter, "fr-FR,fr;q=0.9")).isEqualTo("zh-CN|zh-CN|[zh-CN]|fr-FR,fr;q=0.9");

		// 进来之前已经有当前语言的，结束后换回原来那个
		LocaleContextHolder.setLocale(Locale.JAPANESE);
		assertThat(seenDownstream(filter, "en")).startsWith("en|");
		assertThat(LocaleContextHolder.getLocale()).isEqualTo(Locale.JAPANESE);
	}

	@Test
	@DisplayName("后面的处理抛了异常：异常照常往外抛，当前语言照样还原")
	void restoresOnFailure() {
		LocaleContextFilter filter = new LocaleContextFilter(() -> null, negotiator);
		FilterChain failing = (request, response) -> {
			throw new ServletException("boom");
		};

		assertThatThrownBy(() -> filter.doFilter(request("en"), new MockHttpServletResponse(), failing))
			.isInstanceOf(ServletException.class);
		assertThat(LocaleContextHolder.getLocaleContext()).isNull();
	}

	@Test
	@DisplayName("语言由 localeResolver 定；它到第一次请求时才去取，取一次就记住")
	void resolverLookedUpOnce() throws Exception {
		AtomicInteger lookups = new AtomicInteger();
		Supplier<LocaleResolver> supplier = () -> {
			lookups.incrementAndGet();
			return new FixedLocaleResolver(Locale.FRENCH);
		};

		LocaleContextFilter filter = new LocaleContextFilter(supplier, negotiator);
		assertThat(lookups).hasValue(0);

		for (int i = 0; i < 3; i++) {
			assertThat(seenDownstream(filter, "en")).isEqualTo("fr|fr|[fr]|en");
		}
		assertThat(lookups).hasValue(1);
	}

	@Test
	@DisplayName("localeResolver 在这个阶段定不出语言（报错或者给空）：按请求头定，请求照常")
	void resolverCannotDecide() throws Exception {
		LocaleResolver throwing = resolver(request -> {
			throw new IllegalStateException("还不知道登录用户是谁");
		});
		LocaleResolver empty = resolver(request -> null);

		assertThat(seenDownstream(new LocaleContextFilter(() -> throwing, negotiator), "en")).startsWith("en|en|");
		assertThat(seenDownstream(new LocaleContextFilter(() -> throwing, negotiator), null)).startsWith("zh-CN|");
		assertThat(seenDownstream(new LocaleContextFilter(() -> empty, negotiator), "en")).startsWith("en|en|");
	}

	@Test
	@DisplayName("取 localeResolver 本身就失败：按请求头定，日志里提醒一次，之后不再去取")
	void resolverUnavailable(CapturedOutput output) throws Exception {
		AtomicInteger lookups = new AtomicInteger();
		LocaleContextFilter filter = new LocaleContextFilter(() -> {
			lookups.incrementAndGet();
			throw new IllegalStateException("建不起来");
		}, negotiator);

		for (int i = 0; i < 3; i++) {
			assertThat(seenDownstream(filter, "en")).startsWith("en|en|");
		}
		assertThat(lookups).hasValue(1);
		assertThat(StringUtils.countOccurrencesOf(output.toString(), "取 localeResolver 失败")).isEqualTo(1);
	}

	@Test
	@DisplayName("没有这个过滤器时的样子：没带请求头，过滤器里取到的是请求自己说的英文，和控制器里不一样")
	void withoutTheFilter() {
		runner.run(context -> {
			MockMvc mvc = MockMvcBuilders.webAppContextSetup(context)
				.addFilters(new RequestContextFilter(), new GuardFilter())
				.build();

			assertThat(body(mvc, get("/blocked"), null)).isEqualTo("User not found|en|en");
			assertThat(body(mvc, get("/locale"), null)).contains("zh-CN|zh-CN");
		});
	}

	@Test
	@DisplayName("排在 Spring 的 RequestContextFilter 后面的过滤器：自己写响应时翻译出来的语言和控制器里一致")
	void filterAfterRequestContextFilter() {
		runner.run(context -> {
			assertThat(context).hasNotFailed();
			MockMvc mvc = MockMvcBuilders.webAppContextSetup(context)
				.addFilters(context.getBean(LocaleContextFilter.class), new RequestContextFilter(), new GuardFilter())
				.build();

			assertGuardAndController(mvc);
		});
	}

	@Test
	@DisplayName("排在 Spring 的 RequestContextFilter 前面的过滤器：也一样")
	void filterBeforeRequestContextFilter() {
		runner.run(context -> {
			MockMvc mvc = MockMvcBuilders.webAppContextSetup(context)
				.addFilters(context.getBean(LocaleContextFilter.class), new GuardFilter(), new RequestContextFilter())
				.build();

			assertGuardAndController(mvc);
		});
	}

	@Test
	@DisplayName("应用自己注册了 localeResolver：过滤器里的语言也按它定，和控制器里一致")
	void customLocaleResolver() {
		runner.withUserConfiguration(JapaneseOnlyConfig.class).run(context -> {
			MockMvc mvc = MockMvcBuilders.webAppContextSetup(context)
				.addFilters(context.getBean(LocaleContextFilter.class), new RequestContextFilter(), new GuardFilter())
				.build();

			assertThat(body(mvc, get("/blocked"), "en")).isEqualTo("ユーザーが存在しません|ja|ja");
			assertThat(body(mvc, get("/locale"), "en")).contains("ja|ja|ja");
		});
	}

	@Test
	@DisplayName("spring.web.locale-resolver=fixed 把语言定死：过滤器里也是定死的那个语言")
	void fixedLocale() {
		runner.withPropertyValues("spring.web.locale-resolver=fixed", "spring.web.locale=en").run(context -> {
			MockMvc mvc = MockMvcBuilders.webAppContextSetup(context)
				.addFilters(context.getBean(LocaleContextFilter.class), new RequestContextFilter(), new GuardFilter())
				.build();

			assertThat(body(mvc, get("/blocked"), "zh-CN")).isEqualTo("User not found|en|en");
			assertThat(body(mvc, get("/blocked"), null)).isEqualTo("User not found|en|en");
		});
	}

	private static void assertGuardAndController(MockMvc mvc) throws Exception {
		// 没带请求头：默认的简体中文，不是请求自己说的英文
		assertThat(body(mvc, get("/blocked"), null)).isEqualTo("用户不存在|zh-CN|zh-CN");
		assertThat(body(mvc, get("/blocked"), "en-US,en;q=0.9")).isEqualTo("User not found|en|en");
		assertThat(body(mvc, get("/blocked"), "zh-TW")).isEqualTo("使用者不存在|zh-TW|zh-TW");
		// 不支持的语言：默认语言
		assertThat(body(mvc, get("/blocked"), "fr-FR,fr;q=0.9")).isEqualTo("用户不存在|zh-CN|zh-CN");

		// 放行到控制器的请求：控制器里的语言、问请求要到的语言也是同一个
		assertThat(body(mvc, get("/locale"), null)).contains("zh-CN|zh-CN|zh-CN");
		assertThat(body(mvc, get("/locale"), "en-GB,en;q=0.9")).contains("en|en|en");
		assertThat(body(mvc, get("/locale"), "ko")).contains("zh-CN|zh-CN|zh-CN");

		// 请求结束后不留下当前语言
		assertThat(LocaleContextHolder.getLocaleContext()).isNull();
	}

	/** 过一遍过滤器，返回后面看到的：当前语言|问请求要到的语言|请求给的语言列表|请求头原文 */
	private static String seenDownstream(LocaleContextFilter filter, String acceptLanguage) throws Exception {
		AtomicReference<String> seen = new AtomicReference<>();
		FilterChain chain = (request, response) -> {
			HttpServletRequest http = (HttpServletRequest) request;
			seen.set(LocaleContextHolder.getLocale().toLanguageTag() + "|" + http.getLocale().toLanguageTag() + "|"
				+ Collections.list(http.getLocales()).stream().map(Locale::toLanguageTag).toList() + "|"
				+ http.getHeader(HttpHeaders.ACCEPT_LANGUAGE));
		};
		filter.doFilter(request(acceptLanguage), new MockHttpServletResponse(), chain);
		return seen.get();
	}

	private static MockHttpServletRequest request(String acceptLanguage) {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/any");
		if (acceptLanguage != null) {
			request.addHeader(HttpHeaders.ACCEPT_LANGUAGE, acceptLanguage);
		}
		return request;
	}

	private static LocaleResolver resolver(Function<HttpServletRequest, Locale> logic) {
		return new LocaleResolver() {

			@Override
			public Locale resolveLocale(HttpServletRequest request) {
				return logic.apply(request);
			}

			@Override
			public void setLocale(HttpServletRequest request, HttpServletResponse response, Locale locale) {
				throw new UnsupportedOperationException();
			}
		};
	}

	/** @param acceptLanguage 请求头 Accept-Language 的值；传 null 表示不带这个头 */
	private static String body(MockMvc mvc, MockHttpServletRequestBuilder request, String acceptLanguage)
			throws Exception {
		if (acceptLanguage != null) {
			request.header(HttpHeaders.ACCEPT_LANGUAGE, acceptLanguage);
		}
		return mvc.perform(request).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
	}

	/**
	 * 一个自己写响应的过滤器（比如限流、演示环境拦截）：拦下 /blocked，不往后走，
	 * 写出「翻译后的提示语|当前语言|问请求要到的语言」。
	 */
	private static final class GuardFilter extends OncePerRequestFilter {

		@Override
		protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
				throws ServletException, IOException {
			if (!"/blocked".equals(request.getRequestURI())) {
				chain.doFilter(request, response);
				return;
			}
			response.setCharacterEncoding(StandardCharsets.UTF_8.name());
			response.setContentType("text/plain");
			response.getWriter().write(I18n.translate("用户不存在") + "|" + I18n.locale().toLanguageTag() + "|"
				+ request.getLocale().toLanguageTag());
		}
	}

	@Configuration(proxyBeanMethods = false)
	static class DemoConfig {

		@Bean
		LocaleController localeController() {
			return new LocaleController();
		}
	}

	@Configuration(proxyBeanMethods = false)
	static class JapaneseOnlyConfig {

		@Bean
		LocaleResolver localeResolver() {
			return new FixedLocaleResolver(Locale.JAPANESE);
		}
	}

	@RestController
	static class LocaleController {

		/** 控制器里看到的：I18n 的当前语言|Spring 的当前语言|问请求要到的语言 */
		@GetMapping("/locale")
		R<String> locale(HttpServletRequest request) {
			return R.data(I18n.locale().toLanguageTag() + "|" + LocaleContextHolder.getLocale().toLanguageTag() + "|"
				+ request.getLocale().toLanguageTag());
		}
	}
}
