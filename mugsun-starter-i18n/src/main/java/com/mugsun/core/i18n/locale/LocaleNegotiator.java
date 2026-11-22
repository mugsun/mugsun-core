package com.mugsun.core.i18n.locale;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 根据请求头 {@code Accept-Language} 定这一次请求用哪种语言。结果一定是支持清单里的一个：
 *
 * <ul>
 * <li>请求没带这个头、头写得不成形、或者里面的语言一个都不支持：用默认语言。</li>
 * <li>带了多个语言：按浏览器给的优先顺序一个个看，第一个对得上的就用它。</li>
 * <li>怎么算对得上：先找语言和地区都一样的；没有就找只写了语言的（请求 en-US，清单里有 en）；
 * 再没有就找同一种语言的其它地区（请求 zh-TW，清单里只有 zh-CN）。</li>
 * </ul>
 *
 * <p>不依赖 Servlet，网关这类不是 Spring MVC 的应用也可以直接用。
 */
public class LocaleNegotiator {

	/** 记住多少种不同的请求头写法。浏览器发来的写法就那么几种，超过这个数的不再记，免得被随手编的请求头撑大 */
	private static final int CACHE_LIMIT = 256;

	/** 超过这个长度的请求头不记 */
	private static final int CACHEABLE_LENGTH = 128;

	private final Locale defaultLocale;
	private final List<Locale> supportedLocales;
	private final Map<String, Locale> cache = new ConcurrentHashMap<>();

	/**
	 * @param defaultLocale 默认语言，总是排在支持清单的第一位
	 * @param supportedLocales 除默认语言以外还支持哪些语言，可以为空
	 */
	public LocaleNegotiator(Locale defaultLocale, Collection<Locale> supportedLocales) {
		if (defaultLocale == null || defaultLocale.getLanguage().isEmpty()) {
			throw new IllegalArgumentException("默认语言不能为空");
		}
		this.defaultLocale = defaultLocale;
		LinkedHashSet<Locale> all = new LinkedHashSet<>();
		all.add(defaultLocale);
		if (supportedLocales != null) {
			for (Locale locale : supportedLocales) {
				if (locale != null && !locale.getLanguage().isEmpty()) {
					all.add(locale);
				}
			}
		}
		this.supportedLocales = List.copyOf(all);
	}

	public Locale getDefaultLocale() {
		return defaultLocale;
	}

	/** 支持清单，默认语言在第一位 */
	public List<Locale> getSupportedLocales() {
		return supportedLocales;
	}

	/**
	 * @param acceptLanguage 请求头 Accept-Language 的原文，没有就传 null
	 * @return 这次请求用的语言，一定是支持清单里的一个
	 */
	public Locale negotiate(String acceptLanguage) {
		if (acceptLanguage == null || acceptLanguage.isBlank()) {
			return defaultLocale;
		}
		Locale cached = cache.get(acceptLanguage);
		if (cached != null) {
			return cached;
		}
		Locale result = doNegotiate(acceptLanguage);
		if (acceptLanguage.length() <= CACHEABLE_LENGTH && cache.size() < CACHE_LIMIT) {
			cache.put(acceptLanguage, result);
		}
		return result;
	}

	private Locale doNegotiate(String acceptLanguage) {
		List<Locale.LanguageRange> ranges;
		try {
			// 按权重从高到低排好的
			ranges = Locale.LanguageRange.parse(acceptLanguage);
		} catch (IllegalArgumentException e) {
			return defaultLocale;
		}
		for (Locale.LanguageRange range : ranges) {
			// q=0 的意思是「不要这种语言」
			if (range.getWeight() <= 0) {
				continue;
			}
			Locale wanted = Locale.forLanguageTag(range.getRange());
			// 「*」（什么语言都行）解析出来没有语言，跳过后落到默认语言
			if (wanted.getLanguage().isEmpty()) {
				continue;
			}
			Locale matched = match(wanted);
			if (matched != null) {
				return matched;
			}
		}
		return defaultLocale;
	}

	private Locale match(Locale wanted) {
		Locale languageOnly = null;
		Locale sameLanguage = null;
		for (Locale candidate : supportedLocales) {
			if (!candidate.getLanguage().equals(wanted.getLanguage()) || scriptConflicts(candidate, wanted)) {
				continue;
			}
			if (candidate.getCountry().equals(wanted.getCountry())) {
				return candidate;
			}
			if (languageOnly == null && candidate.getCountry().isEmpty()) {
				languageOnly = candidate;
			}
			if (sameLanguage == null) {
				sameLanguage = candidate;
			}
		}
		return languageOnly != null ? languageOnly : sameLanguage;
	}

	/** 两边都写了书写系统（比如简体 Hans、繁体 Hant）而且不一样，才算对不上 */
	private static boolean scriptConflicts(Locale a, Locale b) {
		return !a.getScript().isEmpty() && !b.getScript().isEmpty() && !a.getScript().equals(b.getScript());
	}
}
