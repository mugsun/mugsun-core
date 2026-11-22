package com.mugsun.core.i18n.locale;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("按请求头定语言")
class LocaleNegotiatorTest {

	private static final Locale ZH_CN = Locale.SIMPLIFIED_CHINESE;

	/** 默认简体中文，另外支持英文：和只放了一份英文翻译文件的应用一样 */
	private final LocaleNegotiator negotiator = new LocaleNegotiator(ZH_CN, List.of(Locale.ENGLISH));

	@Test
	@DisplayName("没带请求头、请求头是空白：用默认语言")
	void noHeader() {
		assertThat(negotiator.negotiate(null)).isEqualTo(ZH_CN);
		assertThat(negotiator.negotiate("")).isEqualTo(ZH_CN);
		assertThat(negotiator.negotiate("   ")).isEqualTo(ZH_CN);
	}

	@Test
	@DisplayName("要英文就给英文，带地区、大小写不同都算")
	void english() {
		assertThat(negotiator.negotiate("en")).isEqualTo(Locale.ENGLISH);
		assertThat(negotiator.negotiate("en-US")).isEqualTo(Locale.ENGLISH);
		assertThat(negotiator.negotiate("EN-us")).isEqualTo(Locale.ENGLISH);
		assertThat(negotiator.negotiate("en-US,en;q=0.9")).isEqualTo(Locale.ENGLISH);
	}

	@Test
	@DisplayName("一次带多个语言：按优先顺序找第一个支持的")
	void firstSupportedWins() {
		assertThat(negotiator.negotiate("fr-FR,fr;q=0.9,en;q=0.8,zh-CN;q=0.7")).isEqualTo(Locale.ENGLISH);
		// 权重高的优先，和写的先后无关
		assertThat(negotiator.negotiate("en;q=0.5,zh-CN")).isEqualTo(ZH_CN);
		assertThat(negotiator.negotiate("zh-CN,zh;q=0.9,en;q=0.8")).isEqualTo(ZH_CN);
	}

	@Test
	@DisplayName("中文的各种写法都落到简体中文")
	void chineseVariants() {
		for (String header : List.of("zh-CN", "zh", "zh-TW", "zh-HK", "zh-Hans-CN")) {
			assertThat(negotiator.negotiate(header)).as(header).isEqualTo(ZH_CN);
		}
	}

	@Test
	@DisplayName("不支持的语言、写得不成形的请求头、星号：用默认语言")
	void unsupportedOrMalformed() {
		for (String header : List.of("fr", "ja-JP,ko;q=0.5", "*", "@@@", "en;q=abc", "en;q=7")) {
			assertThat(negotiator.negotiate(header)).as(header).isEqualTo(ZH_CN);
		}
	}

	@Test
	@DisplayName("权重写 0 表示不要这种语言")
	void zeroWeightSkipped() {
		assertThat(negotiator.negotiate("en;q=0")).isEqualTo(ZH_CN);
		assertThat(negotiator.negotiate("en;q=0,fr;q=0.5")).isEqualTo(ZH_CN);
	}

	@Test
	@DisplayName("清单里只有带地区的：只说语言对得上，同一种语言的别的地区也对得上")
	void regionFallback() {
		Locale brazil = Locale.forLanguageTag("pt-BR");
		LocaleNegotiator regional = new LocaleNegotiator(ZH_CN, List.of(Locale.US, brazil));

		assertThat(regional.negotiate("en")).isEqualTo(Locale.US);
		assertThat(regional.negotiate("en-GB")).isEqualTo(Locale.US);
		assertThat(regional.negotiate("pt-PT")).isEqualTo(brazil);
	}

	@Test
	@DisplayName("语言和地区都一样的，优先于只有语言一样的")
	void exactBeatsLanguageOnly() {
		LocaleNegotiator many = new LocaleNegotiator(ZH_CN,
			List.of(Locale.ENGLISH, Locale.UK, Locale.TRADITIONAL_CHINESE));

		assertThat(many.negotiate("en-GB")).isEqualTo(Locale.UK);
		assertThat(many.negotiate("en-AU")).isEqualTo(Locale.ENGLISH);
		assertThat(many.negotiate("zh-TW")).isEqualTo(Locale.TRADITIONAL_CHINESE);
		assertThat(many.negotiate("zh-HK")).isEqualTo(ZH_CN);
	}

	@Test
	@DisplayName("写明了简体、繁体时不会互相串")
	void scripts() {
		Locale traditional = Locale.forLanguageTag("zh-Hant");
		LocaleNegotiator withScript = new LocaleNegotiator(ZH_CN, List.of(traditional));

		assertThat(withScript.negotiate("zh-Hant-TW")).isEqualTo(traditional);
		assertThat(withScript.negotiate("zh-Hans")).isEqualTo(ZH_CN);
	}

	@Test
	@DisplayName("默认语言总在支持清单的第一位，重复的、空的不算")
	void supportedList() {
		LocaleNegotiator messy = new LocaleNegotiator(ZH_CN,
			Arrays.asList(Locale.ENGLISH, ZH_CN, null, Locale.ROOT, Locale.ENGLISH));

		assertThat(messy.getDefaultLocale()).isEqualTo(ZH_CN);
		assertThat(messy.getSupportedLocales()).containsExactly(ZH_CN, Locale.ENGLISH);
		assertThat(new LocaleNegotiator(ZH_CN, null).negotiate("en")).isEqualTo(ZH_CN);
	}

	@Test
	@DisplayName("没有默认语言：直接报错")
	void defaultRequired() {
		assertThatThrownBy(() -> new LocaleNegotiator(null, List.of())).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new LocaleNegotiator(Locale.ROOT, List.of()))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("随手编的请求头再多，结果照样对，记住的写法不超过上限")
	void cacheBounded() {
		for (int i = 0; i < 2000; i++) {
			assertThat(negotiator.negotiate("en-x-" + i)).isEqualTo(Locale.ENGLISH);
		}
		assertThat(negotiator.negotiate("en-x-" + "a".repeat(8) + "-b".repeat(100))).isEqualTo(Locale.ENGLISH);

		Map<?, ?> cache = (Map<?, ?>) ReflectionTestUtils.getField(negotiator, "cache");
		assertThat(cache).hasSizeLessThanOrEqualTo(256);
	}
}
