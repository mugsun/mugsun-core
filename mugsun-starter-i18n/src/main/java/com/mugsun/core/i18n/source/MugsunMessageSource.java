package com.mugsun.core.i18n.source;

import com.mugsun.core.i18n.locale.LocaleChain;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.support.AbstractMessageSource;

import java.text.MessageFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 按语言查文案。查找顺序：
 *
 * <ol>
 * <li>先问 {@link MessageProvider}（比如数据库里在线改的文案），有就用。</li>
 * <li>再查翻译文件，从具体到笼统：要 zh_CN 时先找 zh_CN 的，再找 zh 的，最后找不分语言的兜底文案。</li>
 * <li>都没有就是没找到。不会退到服务器操作系统的语言：同一份程序在中文系统和英文系统上结果一样。</li>
 * </ol>
 *
 * <p>文案的键可以是点分的码（{@code user.not.found}），也可以直接是中文原文（{@code 用户不存在}）。
 * 不带参数取文案时原样返回；带参数时按 {@link MessageFormat} 的规则填 {@code {0}}、{@code {1}}。
 */
public class MugsunMessageSource extends AbstractMessageSource {

	private static final Logger log = LoggerFactory.getLogger(MugsunMessageSource.class);

	/** 记住多少种语言的查找顺序和占位格式。正常只有支持清单里那几种，超过这个数的现算不记 */
	private static final int LOCALE_CACHE_LIMIT = 256;

	/** 文案来源出错时，隔多久才再提醒一次 */
	private static final long PROVIDER_WARN_INTERVAL_MS = 60_000L;

	private final MessageBundles bundles;
	private final Supplier<List<MessageProvider>> providers;
	private final Map<Locale, List<Locale>> chains = new ConcurrentHashMap<>();
	private final Map<Locale, Map<String, MessageFormat>> formats = new ConcurrentHashMap<>();
	private volatile long lastProviderWarnAt;

	public MugsunMessageSource(MessageBundles bundles) {
		this(bundles, List::of);
	}

	/**
	 * @param bundles 翻译文件读出来的文案
	 * @param providers 翻译文件之外的文案来源，每次查文案时取一次（这样可以等到真正用时才去容器里找）
	 */
	public MugsunMessageSource(MessageBundles bundles, Supplier<List<MessageProvider>> providers) {
		this.bundles = bundles;
		this.providers = providers;
	}

	/** 有翻译文件的语言 */
	public Set<Locale> getLocales() {
		return bundles.locales();
	}

	public MessageBundles getBundles() {
		return bundles;
	}

	@Override
	protected String resolveCodeWithoutArguments(String code, Locale locale) {
		return find(code, locale);
	}

	@Override
	protected MessageFormat resolveCode(String code, Locale locale) {
		String text = find(code, locale);
		if (text == null) {
			return null;
		}
		Map<String, MessageFormat> cached = formats.get(locale);
		if (cached == null) {
			if (formats.size() >= LOCALE_CACHE_LIMIT) {
				return createMessageFormat(text, locale);
			}
			cached = formats.computeIfAbsent(locale, key -> new ConcurrentHashMap<>());
		}
		return cached.computeIfAbsent(text, pattern -> createMessageFormat(pattern, locale));
	}

	private String find(String code, Locale locale) {
		if (code == null) {
			return null;
		}
		for (MessageProvider provider : providers.get()) {
			try {
				String text = provider.find(code, locale);
				if (text != null) {
					return text;
				}
			} catch (RuntimeException e) {
				warnProvider(provider, e);
			}
		}
		for (Locale candidate : chain(locale)) {
			String text = bundles.of(candidate).get(code);
			if (text != null) {
				return text;
			}
		}
		return null;
	}

	private List<Locale> chain(Locale locale) {
		List<Locale> chain = chains.get(locale);
		if (chain == null) {
			chain = LocaleChain.of(locale);
			if (chains.size() < LOCALE_CACHE_LIMIT) {
				chains.put(locale, chain);
			}
		}
		return chain;
	}

	private void warnProvider(MessageProvider provider, RuntimeException e) {
		long now = System.currentTimeMillis();
		if (now - lastProviderWarnAt >= PROVIDER_WARN_INTERVAL_MS) {
			lastProviderWarnAt = now;
			log.warn("文案来源 {} 出错，这次当作没找到，改用翻译文件里的文案：{}", provider.getClass().getName(), e.toString());
		}
	}
}
