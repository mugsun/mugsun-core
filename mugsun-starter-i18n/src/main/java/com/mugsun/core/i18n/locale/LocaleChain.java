package com.mugsun.core.i18n.locale;

import java.util.IllformedLocaleException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * 找文案时从具体到笼统的查找顺序。比如 zh_CN：先找 zh_CN 的翻译，再找 zh 的，
 * 最后找不分语言的兜底文案（{@link Locale#ROOT}）。
 */
public final class LocaleChain {

	private LocaleChain() {
	}

	/**
	 * @param locale 要找的语言，可以为 null
	 * @return 查找顺序，最后一个总是 {@link Locale#ROOT}；变体和扩展（比如日历、数字习惯）不参与查找
	 */
	public static List<Locale> of(Locale locale) {
		if (locale == null || locale.getLanguage().isEmpty()) {
			return List.of(Locale.ROOT);
		}
		String language = locale.getLanguage();
		String script = locale.getScript();
		String country = locale.getCountry();
		LinkedHashSet<Locale> chain = new LinkedHashSet<>(8);
		if (!script.isEmpty() && !country.isEmpty()) {
			chain.add(build(language, script, country));
		}
		if (!script.isEmpty()) {
			chain.add(build(language, script, ""));
		}
		if (!country.isEmpty()) {
			chain.add(build(language, "", country));
		}
		chain.add(build(language, "", ""));
		chain.add(Locale.ROOT);
		return List.copyOf(chain);
	}

	private static Locale build(String language, String script, String country) {
		try {
			return new Locale.Builder().setLanguage(language).setScript(script).setRegion(country).build();
		} catch (IllformedLocaleException e) {
			// 用老式构造方法拼出来的不成形的语言：当作没有这一级
			return Locale.ROOT;
		}
	}
}
