package com.mugsun.core.i18n.source;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.NoSuchMessageException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("按语言查文案")
@ExtendWith(OutputCaptureExtension.class)
class MugsunMessageSourceTest {

	private static final Locale ZH_CN = Locale.SIMPLIFIED_CHINESE;

	private static final MessageBundles BUNDLES = MessageBundles.of(Map.of(
		Locale.ROOT, Map.of("level", "root", "only-root", "兜底文案", "greeting", "你好，{0}"),
		Locale.CHINESE, Map.of("level", "zh", "only-zh", "中文"),
		ZH_CN, Map.of("level", "zh_CN"),
		Locale.ENGLISH, Map.of("level", "en", "greeting", "Hello, {0}", "用户不存在", "User not found",
			"raw", "It's {0}", "broken", "Broken {0")));

	private final MugsunMessageSource source = new MugsunMessageSource(BUNDLES);

	@Test
	@DisplayName("从具体到笼统：先找带地区的，再找只有语言的，最后找不分语言的兜底文案")
	void specificToGeneral() {
		assertThat(text("level", ZH_CN)).isEqualTo("zh_CN");
		assertThat(text("level", Locale.TRADITIONAL_CHINESE)).isEqualTo("zh");
		assertThat(text("level", Locale.US)).isEqualTo("en");
		assertThat(text("level", Locale.JAPANESE)).isEqualTo("root");
		assertThat(text("only-zh", ZH_CN)).isEqualTo("中文");
		assertThat(text("only-root", Locale.ENGLISH)).isEqualTo("兜底文案");
	}

	@Test
	@DisplayName("没有的文案：查不到就是查不到，不会退到服务器操作系统的语言")
	void notFound() {
		Locale system = Locale.getDefault();
		Locale.setDefault(Locale.ENGLISH);
		try {
			// 这一条只有英文翻译：要日文时，不会因为服务器是英文系统就给英文
			assertThat(text("用户不存在", Locale.JAPANESE)).isNull();
			assertThat(text("missing", Locale.ENGLISH)).isNull();
			assertThatThrownBy(() -> source.getMessage("missing", null, Locale.ENGLISH))
				.isInstanceOf(NoSuchMessageException.class);
		} finally {
			Locale.setDefault(system);
		}
	}

	@Test
	@DisplayName("键可以直接是中文原文")
	void originalTextAsKey() {
		assertThat(text("用户不存在", Locale.ENGLISH)).isEqualTo("User not found");
		assertThat(text("用户不存在", Locale.UK)).isEqualTo("User not found");
		assertThat(text("用户不存在", ZH_CN)).isNull();
	}

	@Test
	@DisplayName("带参数：按 MessageFormat 的规则填占位；不带参数：原样返回")
	void arguments() {
		assertThat(source.getMessage("greeting", new Object[] {"Tom"}, Locale.ENGLISH)).isEqualTo("Hello, Tom");
		assertThat(source.getMessage("greeting", new Object[] {"小明"}, ZH_CN)).isEqualTo("你好，小明");
		assertThat(text("greeting", Locale.ENGLISH)).isEqualTo("Hello, {0}");
		// 不带参数时不解析，单引号写一个就行
		assertThat(text("raw", Locale.ENGLISH)).isEqualTo("It's {0}");
	}

	@Test
	@DisplayName("译文的占位写坏了：带参数取的时候报错，怎么兜底由上一层定")
	void brokenPattern() {
		assertThatThrownBy(() -> source.getMessage("broken", new Object[] {"x"}, Locale.ENGLISH))
			.isInstanceOf(IllegalArgumentException.class);
		assertThat(text("broken", Locale.ENGLISH)).isEqualTo("Broken {0");
	}

	@Test
	@DisplayName("别的文案来源排在翻译文件前面：它有就用它的，它没有再查翻译文件")
	void providersFirst() {
		MessageProvider provider = (code, locale) ->
			"greeting".equals(code) && Locale.ENGLISH.equals(locale) ? "Hi, {0}" : null;
		MugsunMessageSource withProvider = new MugsunMessageSource(BUNDLES, () -> List.of(provider));

		assertThat(withProvider.getMessage("greeting", new Object[] {"Tom"}, Locale.ENGLISH)).isEqualTo("Hi, Tom");
		assertThat(withProvider.getMessage("greeting", new Object[] {"小明"}, ZH_CN)).isEqualTo("你好，小明");
		assertThat(withProvider.getMessage("level", null, null, Locale.ENGLISH)).isEqualTo("en");
	}

	@Test
	@DisplayName("有几个文案来源时按顺序问，第一个答上来的算数")
	void providerOrder() {
		List<String> asked = new ArrayList<>();
		MessageProvider first = (code, locale) -> {
			asked.add("first");
			return "k".equals(code) ? "from first" : null;
		};
		MessageProvider second = (code, locale) -> {
			asked.add("second");
			return "from second";
		};
		MugsunMessageSource withProviders = new MugsunMessageSource(BUNDLES, () -> List.of(first, second));

		assertThat(withProviders.getMessage("k", null, null, Locale.ENGLISH)).isEqualTo("from first");
		assertThat(asked).containsExactly("first");
		assertThat(withProviders.getMessage("other", null, null, Locale.ENGLISH)).isEqualTo("from second");
		assertThat(asked).containsExactly("first", "first", "second");
	}

	@Test
	@DisplayName("文案来源出错：当作没找到，改用翻译文件里的；日志里提醒一次，不是每次都提醒")
	void providerFailure(CapturedOutput output) {
		AtomicInteger calls = new AtomicInteger();
		MessageProvider failing = (code, locale) -> {
			calls.incrementAndGet();
			throw new IllegalStateException("数据库连不上");
		};
		MugsunMessageSource withProvider = new MugsunMessageSource(BUNDLES, () -> List.of(failing));

		for (int i = 0; i < 50; i++) {
			assertThat(withProvider.getMessage("level", null, null, Locale.ENGLISH)).isEqualTo("en");
		}

		assertThat(calls).hasValue(50);
		assertThat(StringUtils.countOccurrencesOf(output.toString(), "这次当作没找到")).isEqualTo(1);
		assertThat(output).contains("数据库连不上");
	}

	@Test
	@DisplayName("文案来源每次查文案时现取：可以等到真正用的时候再准备好")
	void providersResolvedOnEachLookup() {
		List<MessageProvider> providers = new ArrayList<>();
		MugsunMessageSource lazy = new MugsunMessageSource(BUNDLES, () -> providers);

		assertThat(lazy.getMessage("level", null, null, Locale.ENGLISH)).isEqualTo("en");

		providers.add((code, locale) -> "late");

		assertThat(lazy.getMessage("level", null, null, Locale.ENGLISH)).isEqualTo("late");
	}

	@Test
	@DisplayName("有翻译文件的语言：不含不分语言的兜底文案")
	void locales() {
		assertThat(source.getLocales()).containsExactly(Locale.ENGLISH, Locale.CHINESE, ZH_CN);
		assertThat(source.getBundles()).isSameAs(BUNDLES);
	}

	@Test
	@DisplayName("随手编的语言再多，结果照样对，记住的语言不超过上限")
	void cachesBounded() {
		for (int i = 0; i < 1000; i++) {
			Locale odd = new Locale.Builder().setLanguage("en").setExtension('x', "t" + i).build();
			assertThat(source.getMessage("greeting", new Object[] {"Tom"}, odd)).isEqualTo("Hello, Tom");
			assertThat(text("level", odd)).isEqualTo("en");
		}

		assertThat((Map<?, ?>) ReflectionTestUtils.getField(source, "chains")).hasSizeLessThanOrEqualTo(256);
		assertThat((Map<?, ?>) ReflectionTestUtils.getField(source, "formats")).hasSizeLessThanOrEqualTo(256);
	}

	private String text(String code, Locale locale) {
		return source.getMessage(code, null, null, locale);
	}
}
