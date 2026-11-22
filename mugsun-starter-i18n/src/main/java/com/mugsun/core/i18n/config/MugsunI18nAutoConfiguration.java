package com.mugsun.core.i18n.config;

import com.mugsun.core.i18n.locale.LocaleNegotiator;
import com.mugsun.core.i18n.message.I18n;
import com.mugsun.core.i18n.message.MessageTranslator;
import com.mugsun.core.i18n.source.MessageBundles;
import com.mugsun.core.i18n.source.MessageProvider;
import com.mugsun.core.i18n.source.MugsunMessageSource;
import com.mugsun.core.i18n.web.AcceptLanguageLocaleResolver;
import com.mugsun.core.i18n.web.LocaleContextFilter;
import com.mugsun.core.i18n.web.TranslateResponseAdvice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanCreationNotAllowedException;
import org.springframework.beans.factory.BeanCurrentlyInCreationException;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.SearchStrategy;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.support.AbstractApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.support.ResourcePatternUtils;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.web.servlet.LocaleResolver;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * 多语言的装配。引入本模块就生效，不需要任何配置：
 *
 * <ul>
 * <li>翻译文件放在类路径的 {@code i18n/} 目录下就会被读到（规则见 {@link MessageBundles}）；
 * 按 Spring Boot 老习惯放在类路径根目录下的 {@code messages*.properties} 也照样读。</li>
 * <li>请求用哪种语言看请求头 {@code Accept-Language}；没带、或者带的语言不支持，用默认语言（简体中文）。
 * 支持哪些语言不用登记：有翻译文件的语言就支持；简体中文是代码里提示语的原文，不用翻译文件也支持。</li>
 * <li>统一返回体里的提示语在返回前翻译成请求的语言，没有翻译的原样返回。</li>
 * <li>过滤器里取到的当前语言也是这次请求的语言（见 {@link LocaleContextFilter}）。</li>
 * <li>校验注解里 {@code {码}} 形式的提示语、代码里用 {@link I18n} 取的文案，查的是同一套翻译文件。</li>
 * </ul>
 *
 * <p>这里注册的 Bean 都可以被应用自己定义的替换：自己注册名为 {@code messageSource} 的 Bean，文案就从自己的地方查
 * （这时支持哪些语言要用 {@code mugsun.i18n.supported-locales} 写明）；自己注册名为 {@code localeResolver} 的 Bean，
 * 语言就按自己的办法定。
 */
@AutoConfiguration(beforeName = {
	"org.springframework.boot.autoconfigure.context.MessageSourceAutoConfiguration",
	"org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration"})
@ConditionalOnProperty(prefix = "mugsun.i18n", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(I18nProperties.class)
public class MugsunI18nAutoConfiguration {

	private static final Logger log = LoggerFactory.getLogger(MugsunI18nAutoConfiguration.class);

	/** Spring Boot 自己的配置项：翻译文件的名字，默认 messages（类路径根目录下的 messages*.properties） */
	private static final String BOOT_BASENAME_PROPERTY = "spring.messages.basename";

	private static final String BOOT_DEFAULT_BASENAME = "messages";

	/** 代码里写的提示语、框架自带的提示语用的语言：不需要翻译文件就给得出来 */
	private static final Locale SOURCE_LOCALE = Locale.SIMPLIFIED_CHINESE;

	@Bean(name = AbstractApplicationContext.MESSAGE_SOURCE_BEAN_NAME)
	@ConditionalOnMissingBean(name = AbstractApplicationContext.MESSAGE_SOURCE_BEAN_NAME, search = SearchStrategy.CURRENT)
	public MugsunMessageSource messageSource(ResourceLoader resourceLoader, Environment environment,
			ObjectProvider<MessageProvider> providers) {
		List<String> basenames = Binder.get(environment)
			.bind(BOOT_BASENAME_PROPERTY, Bindable.listOf(String.class))
			.orElseGet(() -> List.of(BOOT_DEFAULT_BASENAME));
		MessageBundles bundles = MessageBundles.load(ResourcePatternUtils.getResourcePatternResolver(resourceLoader),
			basenames);
		return new MugsunMessageSource(bundles, new LazyProviders(providers));
	}

	/**
	 * 没写明支持哪些语言时自己定：默认语言、简体中文、有翻译文件的语言。
	 * 简体中文不看有没有翻译文件：代码里写的提示语本来就是简体中文，默认语言改成别的以后，中文请求看到的仍然是中文。
	 * 写明了（{@code mugsun.i18n.supported-locales}）就只认默认语言和写的那几种。
	 */
	@Bean
	@ConditionalOnMissingBean
	public LocaleNegotiator localeNegotiator(I18nProperties properties, ObjectProvider<MugsunMessageSource> messageSource) {
		List<Locale> supported = properties.getSupportedLocales();
		if (supported == null || supported.isEmpty()) {
			supported = new ArrayList<>();
			supported.add(SOURCE_LOCALE);
			MugsunMessageSource source = messageSource.getIfAvailable();
			if (source != null) {
				supported.addAll(source.getLocales());
			}
		}
		return new LocaleNegotiator(properties.getDefaultLocale(), supported);
	}

	@Bean
	@ConditionalOnMissingBean
	public MessageTranslator messageTranslator(
			@Qualifier(AbstractApplicationContext.MESSAGE_SOURCE_BEAN_NAME) MessageSource messageSource,
			LocaleNegotiator negotiator) {
		return new MessageTranslator(messageSource, negotiator);
	}

	/** 不跟着「全局延迟创建」走：静态入口 {@link I18n} 要在启动时就接上翻译器，不能等有人来取这个 Bean */
	@Bean
	@Lazy(false)
	public I18nInstaller i18nInstaller(MessageTranslator translator) {
		return new I18nInstaller(translator);
	}

	/** 只在 Spring MVC 应用里才有的几样：按请求头定语言、让过滤器里也拿到这个语言、翻译返回体里的提示语 */
	@Configuration(proxyBeanMethods = false)
	@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
	@ConditionalOnClass(name = "org.springframework.web.servlet.LocaleResolver")
	static class ServletI18nConfiguration {

		@Bean(name = "localeResolver")
		@ConditionalOnMissingBean(name = "localeResolver")
		@Conditional(LocaleNotFixedCondition.class)
		LocaleResolver localeResolver(LocaleNegotiator negotiator) {
			return new AcceptLanguageLocaleResolver(negotiator);
		}

		/** 语言按名为 localeResolver 的那个 Bean 定（应用自己注册的也算），和控制器里的一致；第一次请求时才去取它 */
		@Bean
		@ConditionalOnMissingBean
		LocaleContextFilter localeContextFilter(
				@Qualifier("localeResolver") ObjectProvider<LocaleResolver> localeResolver,
				LocaleNegotiator negotiator) {
			return new LocaleContextFilter(localeResolver::getIfAvailable, negotiator);
		}

		@Bean
		@ConditionalOnMissingBean
		@ConditionalOnProperty(prefix = "mugsun.i18n", name = "translate-response", havingValue = "true",
			matchIfMissing = true)
		TranslateResponseAdvice translateResponseAdvice(MessageTranslator translator) {
			return new TranslateResponseAdvice(translator);
		}
	}

	/**
	 * 应用用 Spring Boot 的 {@code spring.web.locale-resolver=fixed} 把语言定死时让开，不再按请求头定语言。
	 * 配置项的值大小写都算。
	 */
	static class LocaleNotFixedCondition implements Condition {

		@Override
		public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
			String value = context.getEnvironment().getProperty("spring.web.locale-resolver");
			return value == null || !"fixed".equalsIgnoreCase(value.trim());
		}
	}

	/** 把翻译器装到静态入口 {@link I18n} 上，容器关闭时卸掉 */
	public static final class I18nInstaller implements InitializingBean, DisposableBean {

		private final MessageTranslator translator;

		I18nInstaller(MessageTranslator translator) {
			this.translator = translator;
		}

		@Override
		public void afterPropertiesSet() {
			I18n.install(translator);
		}

		@Override
		public void destroy() {
			I18n.uninstall(translator);
		}
	}

	/**
	 * 翻译文件之外的文案来源，等到第一次查文案时才去容器里找：{@code messageSource} 在容器启动的很早阶段就要建好，
	 * 那时就去找，会把数据库连接这类东西过早地拉起来。
	 *
	 * <p>找的时候文案来源建不起来（比如它要连的数据库不通）：日志里提醒一次，先只用翻译文件里的文案，过一分钟再找。
	 * 有一个建不起来，这段时间里其余的文案来源也一起不用。
	 */
	static final class LazyProviders implements Supplier<List<MessageProvider>> {

		/** 文案来源没能建起来以后，隔多久再试一次 */
		static final long RETRY_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(60);

		private final ObjectProvider<MessageProvider> providers;

		/** 现在是什么时候（纳秒，只用来算间隔）；测试里换成自己拨的钟 */
		private final LongSupplier clock;

		private volatile List<MessageProvider> resolved;

		/** 上一次没能建起来是什么时候；null 表示没有失败过 */
		private volatile Long failedAt;

		LazyProviders(ObjectProvider<MessageProvider> providers) {
			this(providers, System::nanoTime);
		}

		LazyProviders(ObjectProvider<MessageProvider> providers, LongSupplier clock) {
			this.providers = providers;
			this.clock = clock;
		}

		@Override
		public List<MessageProvider> get() {
			List<MessageProvider> current = resolved;
			if (current != null) {
				return current;
			}
			Long failed = failedAt;
			if (failed != null && clock.getAsLong() - failed < RETRY_INTERVAL_NANOS) {
				return List.of();
			}
			try {
				current = providers.orderedStream().toList();
			} catch (RuntimeException e) {
				if (notReadyYet(e)) {
					log.debug("文案来源（MessageProvider）还在创建中或者容器正在关闭，这次只用翻译文件里的文案：{}", e.toString());
				} else {
					// 不能每次查文案都再建一遍（连不上的数据库会把每个请求都拖慢），也不能不声不响
					failedAt = clock.getAsLong();
					log.warn("文案来源（MessageProvider）没能准备好，先只用翻译文件里的文案，一分钟后再试", e);
				}
				return List.of();
			}
			failedAt = null;
			resolved = current;
			return current;
		}

		/** 文案来源自己还在创建中（它创建时又来查文案），或者容器正在关闭：不算出错，这次先不用，下次再找 */
		private static boolean notReadyYet(RuntimeException e) {
			return e instanceof BeansException beans && (beans.contains(BeanCurrentlyInCreationException.class)
				|| beans.contains(BeanCreationNotAllowedException.class));
		}
	}
}
