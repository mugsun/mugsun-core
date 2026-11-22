package com.mugsun.core.i18n.locale;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("找文案时从具体到笼统的查找顺序")
class LocaleChainTest {

	@Test
	@DisplayName("带地区的：先地区，再语言，最后兜底")
	void languageAndCountry() {
		assertThat(LocaleChain.of(Locale.SIMPLIFIED_CHINESE))
			.containsExactly(Locale.SIMPLIFIED_CHINESE, Locale.CHINESE, Locale.ROOT);
	}

	@Test
	@DisplayName("只有语言的：语言，然后兜底")
	void languageOnly() {
		assertThat(LocaleChain.of(Locale.ENGLISH)).containsExactly(Locale.ENGLISH, Locale.ROOT);
	}

	@Test
	@DisplayName("写了简体繁体的：带书写系统的排在前面")
	void withScript() {
		assertThat(LocaleChain.of(Locale.forLanguageTag("zh-Hant-TW"))).containsExactly(
			Locale.forLanguageTag("zh-Hant-TW"), Locale.forLanguageTag("zh-Hant"), Locale.TRADITIONAL_CHINESE,
			Locale.CHINESE, Locale.ROOT);
	}

	@Test
	@DisplayName("日历、数字习惯这类扩展不参与查找")
	void extensionsIgnored() {
		assertThat(LocaleChain.of(Locale.forLanguageTag("en-US-u-ca-buddhist")))
			.containsExactly(Locale.US, Locale.ENGLISH, Locale.ROOT);
	}

	@Test
	@DisplayName("没有语言：只查兜底")
	void noLanguage() {
		assertThat(LocaleChain.of(null)).containsExactly(Locale.ROOT);
		assertThat(LocaleChain.of(Locale.ROOT)).containsExactly(Locale.ROOT);
	}
}
