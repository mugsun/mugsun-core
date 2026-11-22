package com.mugsun.core.i18n.config;

import com.mugsun.core.i18n.locale.LocaleNegotiator;
import com.mugsun.core.i18n.message.I18n;
import com.mugsun.core.i18n.message.MessageTranslator;
import com.mugsun.core.i18n.source.MessageProvider;
import com.mugsun.core.i18n.source.MugsunMessageSource;
import com.mugsun.core.i18n.web.AcceptLanguageLocaleResolver;
import com.mugsun.core.i18n.web.LocaleContextFilter;
import com.mugsun.core.i18n.web.TranslateResponseAdvice;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.LazyInitializationBeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.MessageSourceAutoConfiguration;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.jackson2.autoconfigure.Jackson2AutoConfiguration;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.i18n.FixedLocaleResolver;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** 翻译文件在 src/test/resources 下：英文、繁体中文在 i18n 目录里，日文按 Spring Boot 老习惯放在根目录 */
@DisplayName("多语言的自动装配")
@ExtendWith(OutputCaptureExtension.class)
class MugsunI18nAutoConfigurationTest {

	private static final Locale ZH_CN = Locale.SIMPLIFIED_CHINESE;

	private final ApplicationContextRunner runner = new ApplicationContextRunner().withConfiguration(
		AutoConfigurations.of(MugsunI18nAutoConfiguration.class, MessageSourceAutoConfiguration.class));

	private final WebApplicationContextRunner webRunner = new WebApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(DispatcherServletAutoConfiguration.class, WebMvcAutoConfiguration.class,
			HttpMessageConvertersAutoConfiguration.class, Jackson2AutoConfiguration.class,
			MessageSourceAutoConfiguration.class, MugsunI18nAutoConfiguration.class));

	@Test
	@DisplayName("引入就生效：登记在自动装配清单里")
	void registered() {
		assertThat(ImportCandidates.load(AutoConfiguration.class, getClass().getClassLoader()).getCandidates())
			.contains(MugsunI18nAutoConfiguration.class.getName());
	}

	@Test
	@DisplayName("配置都有默认值：开着，默认简体中文，返回前翻译提示语")
	void defaults() {
		I18nProperties properties = new I18nProperties();

		assertThat(properties.isEnabled()).isTrue();
		assertThat(properties.getDefaultLocale()).isEqualTo(ZH_CN);
		assertThat(properties.getSupportedLocales()).isEmpty();
		assertThat(properties.isTranslateResponse()).isTrue();
	}

	@Test
	@DisplayName("一项不配：翻译文件自动读到，有翻译文件的语言自动算支持，静态入口接上；容器关了就卸掉")
	void zeroConfig() {
		AtomicReference<MessageTranslator> installed = new AtomicReference<>();
		runner.run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context.getBean("messageSource")).isInstanceOf(MugsunMessageSource.class);
			// 默认语言在第一位，后面是有翻译文件的语言
			assertThat(context.getBean(LocaleNegotiator.class).getSupportedLocales())
				.containsExactly(ZH_CN, Locale.ENGLISH, Locale.JAPANESE, Locale.TRADITIONAL_CHINESE);
			assertThat(context.getMessage("用户不存在", null, Locale.ENGLISH)).isEqualTo("User not found");

			MessageTranslator translator = context.getBean(MessageTranslator.class);
			assertThat(translator.getDefaultLocale()).isEqualTo(ZH_CN);
			assertThat(I18n.translator()).isSameAs(translator);
			assertThat(I18n.get(Locale.ENGLISH, "用户不存在")).isEqualTo("User not found");
			// 拿不到当前语言的地方（比如网关）：按请求头定语言，认的是同一份支持清单
			assertThat(I18n.negotiate("en-US,en;q=0.9,zh-CN;q=0.8")).isEqualTo(Locale.ENGLISH);
			assertThat(I18n.negotiate("fr")).isEqualTo(ZH_CN);
			assertThat(I18n.translate(I18n.negotiate("ja"), "用户不存在")).isEqualTo("ユーザーが存在しません");
			installed.set(translator);
		});

		assertThat(installed.get()).isNotNull();
		assertThat(I18n.translator()).isNotSameAs(installed.get());
		assertThat(I18n.get(Locale.ENGLISH, "用户不存在")).isEqualTo("用户不存在");
	}

	@Test
	@DisplayName("不是网页应用（定时任务、命令行）：照样能取文案，只是没有跟请求有关的那几样")
	void nonWeb() {
		runner.run(context -> {
			assertThat(context).hasSingleBean(MessageTranslator.class).hasSingleBean(LocaleNegotiator.class);
			assertThat(context).doesNotHaveBean("localeResolver").doesNotHaveBean(TranslateResponseAdvice.class)
				.doesNotHaveBean(LocaleContextFilter.class);
		});
	}

	@Test
	@DisplayName("是 Servlet 应用但没有 Spring MVC：照样启动，取文案能用，只是没有那几样")
	void servletWithoutSpringMvc() {
		webRunner.withClassLoader(new FilteredClassLoader("org.springframework.web.servlet")).run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context).doesNotHaveBean("localeResolver").doesNotHaveBean(TranslateResponseAdvice.class)
				.doesNotHaveBean("localeContextFilter");
			assertThat(context.getBean(MessageTranslator.class).get(Locale.ENGLISH, "用户不存在"))
				.isEqualTo("User not found");
		});
	}

	@Test
	@DisplayName("网关这类没有 Spring MVC 的应用也能引入：除了只在 Spring MVC 应用里才加载的那几个类，别的类都不引用网页相关的类")
	void noWebReferencesOutsideWebClasses() throws Exception {
		Path classes = Path.of(MugsunI18nAutoConfiguration.class.getProtectionDomain().getCodeSource()
			.getLocation().toURI());
		assertThat(classes).isDirectory();

		List<String> checked = new ArrayList<>();
		List<String> offenders = new ArrayList<>();
		try (Stream<Path> files = Files.walk(classes)) {
			for (Path file : files.filter(path -> path.toString().endsWith(".class")).toList()) {
				String name = classes.relativize(file).toString().replace(File.separatorChar, '/');
				if (name.startsWith("com/mugsun/core/i18n/web/") || name.endsWith("$ServletI18nConfiguration.class")) {
					continue;
				}
				checked.add(name);
				// 类文件里引用到的类名是用斜杠分隔的明文，直接找字符串就行
				String content = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
				for (String forbidden : List.of("jakarta/servlet/", "org/springframework/web/",
						"org/springframework/http/")) {
					if (content.contains(forbidden)) {
						offenders.add(name + " 引用了 " + forbidden);
					}
				}
			}
		}

		assertThat(checked).contains("com/mugsun/core/i18n/config/MugsunI18nAutoConfiguration.class",
			"com/mugsun/core/i18n/message/MessageTranslator.class");
		assertThat(offenders).isEmpty();
	}

	@Test
	@DisplayName("Spring MVC 应用：按请求头定语言的就是这里注册的那个，Spring Boot 自带的让开；过滤器排在前头")
	void webApp() {
		webRunner.run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context).hasSingleBean(LocaleResolver.class).hasSingleBean(TranslateResponseAdvice.class)
				.hasSingleBean(LocaleContextFilter.class);
			assertThat(context.getBean("localeResolver")).isInstanceOf(AcceptLanguageLocaleResolver.class);
			// 要排在 Spring Boot 的 RequestContextFilter（顺序 -105）前面：它会把当前语言设成请求自己说的语言
			assertThat(context.getBean(LocaleContextFilter.class).getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE + 5)
				.isLessThan(-105);
		});
	}

	@Test
	@DisplayName("应用自己注册了 LocaleContextFilter：用应用的，这里的让开")
	void customLocaleContextFilter() {
		LocaleContextFilter custom = new LocaleContextFilter(() -> null, new LocaleNegotiator(Locale.ENGLISH, null));

		webRunner.withBean("myLocaleFilter", LocaleContextFilter.class, () -> custom).run(context -> {
			assertThat(context).hasSingleBean(LocaleContextFilter.class);
			assertThat(context.getBean(LocaleContextFilter.class)).isSameAs(custom);
		});
	}

	@Test
	@DisplayName("mugsun.i18n.enabled=false：什么都不注册，静态入口返回键本身")
	void disabled() {
		webRunner.withPropertyValues("mugsun.i18n.enabled=false").run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context).doesNotHaveBean(MugsunMessageSource.class).doesNotHaveBean(MessageTranslator.class)
				.doesNotHaveBean(LocaleNegotiator.class).doesNotHaveBean(TranslateResponseAdvice.class)
				.doesNotHaveBean(LocaleContextFilter.class).doesNotHaveBean(I18nProperties.class);
			assertThat(context.getBean("localeResolver")).isNotInstanceOf(AcceptLanguageLocaleResolver.class);
			assertThat(I18n.get(Locale.ENGLISH, "用户不存在")).isEqualTo("用户不存在");
		});
	}

	@Test
	@DisplayName("mugsun.i18n.translate-response=false：不翻译返回体，语言照样按请求头定")
	void translateResponseOff() {
		webRunner.withPropertyValues("mugsun.i18n.translate-response=false").run(context -> {
			assertThat(context).doesNotHaveBean(TranslateResponseAdvice.class).hasSingleBean(LocaleContextFilter.class);
			assertThat(context.getBean("localeResolver")).isInstanceOf(AcceptLanguageLocaleResolver.class);
		});
	}

	@Test
	@DisplayName("改默认语言：没带语言的请求用它；简体中文是代码里提示语的原文，没有翻译文件也照样支持")
	void defaultLocale() {
		runner.withPropertyValues("mugsun.i18n.default-locale=en").run(context -> {
			LocaleNegotiator negotiator = context.getBean(LocaleNegotiator.class);

			assertThat(context.getBean(MessageTranslator.class).getDefaultLocale()).isEqualTo(Locale.ENGLISH);
			assertThat(negotiator.getSupportedLocales())
				.containsExactly(Locale.ENGLISH, ZH_CN, Locale.JAPANESE, Locale.TRADITIONAL_CHINESE);
			assertThat(negotiator.negotiate(null)).isEqualTo(Locale.ENGLISH);
			assertThat(negotiator.negotiate("fr")).isEqualTo(Locale.ENGLISH);
			assertThat(negotiator.negotiate("zh-CN,zh;q=0.9")).isEqualTo(ZH_CN);
		});
	}

	@Test
	@DisplayName("写明支持哪些语言：只认默认语言和写的这几种，有翻译文件但没写的不算")
	void supportedLocales() {
		runner.withPropertyValues("mugsun.i18n.supported-locales=en,fr").run(context -> {
			LocaleNegotiator negotiator = context.getBean(LocaleNegotiator.class);

			assertThat(negotiator.getSupportedLocales()).containsExactly(ZH_CN, Locale.ENGLISH, Locale.FRENCH);
			assertThat(negotiator.negotiate("ja")).isEqualTo(ZH_CN);
			assertThat(negotiator.negotiate("fr")).isEqualTo(Locale.FRENCH);
		});
		// 只想要英文一种：默认语言和支持清单都写英文，中文请求也给英文
		runner.withPropertyValues("mugsun.i18n.default-locale=en", "mugsun.i18n.supported-locales=en").run(context -> {
			LocaleNegotiator negotiator = context.getBean(LocaleNegotiator.class);

			assertThat(negotiator.getSupportedLocales()).containsExactly(Locale.ENGLISH);
			assertThat(negotiator.negotiate("zh-CN")).isEqualTo(Locale.ENGLISH);
		});
	}

	@Test
	@DisplayName("spring.messages.basename 改了名字：类路径根目录下按新名字找，i18n 目录下的不受影响")
	void bootBasename() {
		runner.withPropertyValues("spring.messages.basename=texts").run(context -> {
			// 日文翻译在根目录的 messages_ja.properties 里，不读它以后日文就不在支持清单里了
			assertThat(context.getBean(LocaleNegotiator.class).getSupportedLocales())
				.containsExactly(ZH_CN, Locale.ENGLISH, Locale.TRADITIONAL_CHINESE);
			assertThat(context.getMessage("用户不存在", null, Locale.ENGLISH)).isEqualTo("User not found");
		});
	}

	@Test
	@DisplayName("应用自己注册了 messageSource：文案从它那里查；除简体中文外还支持哪些语言要写明")
	void customMessageSource() {
		runner.withUserConfiguration(CustomMessageSourceConfig.class).run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context).doesNotHaveBean(MugsunMessageSource.class);
			assertThat(context.getBean(MessageTranslator.class).get(Locale.ENGLISH, "custom.key"))
				.isEqualTo("from custom");
			assertThat(context.getBean(LocaleNegotiator.class).getSupportedLocales()).containsExactly(ZH_CN);
		});
		runner.withUserConfiguration(CustomMessageSourceConfig.class)
			.withPropertyValues("mugsun.i18n.supported-locales=en")
			.run(context -> assertThat(context.getBean(LocaleNegotiator.class).getSupportedLocales())
				.containsExactly(ZH_CN, Locale.ENGLISH));
	}

	@Test
	@DisplayName("应用自己注册了 localeResolver：语言按它的办法定，这里的让开；提示语照样翻译")
	void customLocaleResolver() {
		webRunner.withUserConfiguration(CustomLocaleResolverConfig.class).run(context -> {
			assertThat(context).hasSingleBean(LocaleResolver.class).hasSingleBean(TranslateResponseAdvice.class);
			assertThat(context.getBean("localeResolver")).isInstanceOf(FixedLocaleResolver.class);
		});
	}

	@Test
	@DisplayName("spring.web.locale-resolver=fixed（大小写都算）：让给 Spring Boot 定死的那个语言")
	void fixedLocale() {
		for (String value : List.of("fixed", "FIXED")) {
			webRunner.withPropertyValues("spring.web.locale-resolver=" + value, "spring.web.locale=en").run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context).hasSingleBean(LocaleResolver.class);
				assertThat(context.getBean("localeResolver")).as(value).isInstanceOf(FixedLocaleResolver.class);
			});
		}
	}

	@Test
	@DisplayName("注册了别的文案来源（比如数据库里的文案）：先按 @Order 的顺序问它们，都没有再查翻译文件")
	void messageProviders() {
		runner.withUserConfiguration(ProvidersConfig.class).run(context -> {
			assertThat(context.getMessage("用户不存在", null, Locale.ENGLISH)).isEqualTo("from first");
			assertThat(context.getMessage("demo.required", null, Locale.ENGLISH)).isEqualTo("from second");
			assertThat(context.getMessage("demo.greeting", new Object[] {"Tom"}, Locale.ENGLISH))
				.isEqualTo("Hello, Tom");
		});
	}

	@Test
	@DisplayName("文案来源自己在创建时就来查文案：不会转圈报错，那一次先用翻译文件里的，建好以后照常用它")
	void providerLookingUpMessagesWhileBeingCreated() {
		SelfLookupProviderConfig.SEEN_WHILE_CREATING.set(null);

		runner.withUserConfiguration(SelfLookupProviderConfig.class).run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(SelfLookupProviderConfig.SEEN_WHILE_CREATING).hasValue("User not found");
			assertThat(context.getMessage("用户不存在", null, Locale.ENGLISH)).isEqualTo("from provider");
		});
	}

	@Test
	@DisplayName("文案来源建不起来（比如它要连的数据库不通）：照常用翻译文件里的文案，日志里提醒一次，不会每次查文案都再建一遍")
	void brokenProvider(CapturedOutput output) {
		BrokenProviderConfig.ATTEMPTS.set(0);

		runner.withUserConfiguration(BrokenProviderConfig.class).run(context -> {
			assertThat(context).hasNotFailed();
			for (int i = 0; i < 20; i++) {
				assertThat(context.getMessage("用户不存在", null, Locale.ENGLISH)).isEqualTo("User not found");
				assertThat(I18n.get(Locale.ENGLISH, "demo.greeting", "Tom")).isEqualTo("Hello, Tom");
			}
			assertThat(BrokenProviderConfig.ATTEMPTS).hasValue(1);
			assertThat(StringUtils.countOccurrencesOf(output.toString(), "没能准备好")).isEqualTo(1);
			assertThat(output).contains("数据库连不上");
		});
	}

	@Test
	@DisplayName("应用开了全局延迟创建（spring.main.lazy-initialization）：静态入口照样在启动时就接上")
	void lazyInitialization() {
		runner
			.withInitializer(context -> context
				.addBeanFactoryPostProcessor(new LazyInitializationBeanFactoryPostProcessor()))
			// 这里故意不从容器里取任何 Bean：静态入口必须在启动时就已经接好
			.run(context -> assertThat(I18n.get(Locale.ENGLISH, "用户不存在")).isEqualTo("User not found"));
	}

	@Test
	@DisplayName("应用自己注册了翻译器：静态入口用的就是它")
	void customTranslator() {
		runner.withUserConfiguration(CustomTranslatorConfig.class).run(context -> {
			assertThat(context).hasSingleBean(MessageTranslator.class);
			assertThat(I18n.translator()).isSameAs(context.getBean(MessageTranslator.class));
			assertThat(I18n.locale()).isEqualTo(Locale.FRENCH);
		});
	}

	@Configuration(proxyBeanMethods = false)
	static class CustomMessageSourceConfig {

		@Bean
		MessageSource messageSource() {
			StaticMessageSource source = new StaticMessageSource();
			source.addMessage("custom.key", Locale.ENGLISH, "from custom");
			return source;
		}
	}

	@Configuration(proxyBeanMethods = false)
	static class CustomLocaleResolverConfig {

		@Bean
		LocaleResolver localeResolver() {
			return new FixedLocaleResolver(Locale.FRENCH);
		}
	}

	@Configuration(proxyBeanMethods = false)
	static class CustomTranslatorConfig {

		@Bean
		MessageTranslator messageTranslator(MessageSource messageSource) {
			return new MessageTranslator(messageSource, Locale.FRENCH);
		}
	}

	@Configuration(proxyBeanMethods = false)
	static class ProvidersConfig {

		@Bean
		@Order(2)
		MessageProvider second() {
			return (code, locale) -> "用户不存在".equals(code) || "demo.required".equals(code) ? "from second" : null;
		}

		@Bean
		@Order(1)
		MessageProvider first() {
			return (code, locale) -> "用户不存在".equals(code) ? "from first" : null;
		}
	}

	@Configuration(proxyBeanMethods = false)
	static class BrokenProviderConfig {

		/** 文案来源被创建了几次 */
		static final AtomicInteger ATTEMPTS = new AtomicInteger();

		/** 标了延迟创建，启动时不会建它；第一次查文案时才建，然后失败 */
		@Bean
		@Lazy
		MessageProvider brokenProvider() {
			ATTEMPTS.incrementAndGet();
			throw new IllegalStateException("数据库连不上");
		}
	}

	@Configuration(proxyBeanMethods = false)
	static class SelfLookupProviderConfig {

		/** 文案来源在自己创建的过程中查到的文案 */
		static final AtomicReference<String> SEEN_WHILE_CREATING = new AtomicReference<>();

		@Bean
		MessageProvider selfLookupProvider(MessageSource messageSource) {
			SEEN_WHILE_CREATING.set(messageSource.getMessage("用户不存在", null, Locale.ENGLISH));
			return (code, locale) -> "用户不存在".equals(code) ? "from provider" : null;
		}
	}
}
