package com.mugsun.core.i18n.message;

import com.mugsun.core.i18n.source.MessageBundles;
import com.mugsun.core.i18n.source.MugsunMessageSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.AbstractMessageSource;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.util.StringUtils;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("取文案和翻译提示语")
@ExtendWith(OutputCaptureExtension.class)
class MessageTranslatorTest {

	private static final Locale ZH_CN = Locale.SIMPLIFIED_CHINESE;

	/** 超过 500 个字的一句：翻译文件里有它，但返回前的翻译不会去查这么长的 */
	private static final String LONG_TEXT = "长".repeat(501);

	private final MessageTranslator translator = new MessageTranslator(new MugsunMessageSource(MessageBundles.of(Map.of(
		Locale.ROOT, Map.of("demo.greeting", "你好，{0}", "demo.required", "必填"),
		Locale.ENGLISH, Map.of("demo.greeting", "Hello, {0}", "demo.required", "This field is required",
			"demo.broken", "Broken {0", "用户不存在", "User not found", "用户 {0} 不存在", "User {0} does not exist",
			"共 {0} 条", "{0} items", LONG_TEXT, "too long")))), ZH_CN);

	@AfterEach
	void resetLocale() {
		LocaleContextHolder.resetLocaleContext();
	}

	@Test
	@DisplayName("当前语言：在请求里是这次请求的语言，不在请求里是默认语言")
	void currentLocale() {
		assertThat(translator.locale()).isEqualTo(ZH_CN);
		assertThat(translator.getDefaultLocale()).isEqualTo(ZH_CN);

		LocaleContextHolder.setLocale(Locale.ENGLISH);

		assertThat(translator.locale()).isEqualTo(Locale.ENGLISH);
	}

	@Test
	@DisplayName("用码取文案：按当前语言给，占位填好")
	void getByCode() {
		assertThat(translator.get("demo.greeting", "小明")).isEqualTo("你好，小明");
		assertThat(translator.get("demo.required")).isEqualTo("必填");

		LocaleContextHolder.setLocale(Locale.ENGLISH);

		assertThat(translator.get("demo.greeting", "Tom")).isEqualTo("Hello, Tom");
		assertThat(translator.get("demo.required")).isEqualTo("This field is required");
	}

	@Test
	@DisplayName("用中文原文做键：有翻译给翻译，没翻译给原文，两种情况占位都填好")
	void getByOriginalText() {
		assertThat(translator.get("用户 {0} 不存在", "tom")).isEqualTo("用户 tom 不存在");
		assertThat(translator.get("用户不存在")).isEqualTo("用户不存在");

		LocaleContextHolder.setLocale(Locale.ENGLISH);

		assertThat(translator.get("用户 {0} 不存在", "tom")).isEqualTo("User tom does not exist");
		assertThat(translator.get("用户不存在")).isEqualTo("User not found");
		// 这一句没有英文翻译：照样显示中文，不报错
		assertThat(translator.get("订单 {0} 已关闭", "A1")).isEqualTo("订单 A1 已关闭");
	}

	@Test
	@DisplayName("指定语言取文案：不看当前语言；语言传 null 用默认语言")
	void getWithLocale() {
		LocaleContextHolder.setLocale(Locale.ENGLISH);

		assertThat(translator.get(ZH_CN, "demo.greeting", "小明")).isEqualTo("你好，小明");
		assertThat(translator.get(Locale.US, "demo.greeting", "Tom")).isEqualTo("Hello, Tom");
		assertThat(translator.get((Locale) null, "demo.greeting", "小明")).isEqualTo("你好，小明");
	}

	@Test
	@DisplayName("数字按语言的习惯加千分位；单号这类不想加的传字符串")
	void numbers() {
		assertThat(translator.get("共 {0} 条", 12345)).isEqualTo("共 12,345 条");
		assertThat(translator.get("共 {0} 条", "12345")).isEqualTo("共 12345 条");

		LocaleContextHolder.setLocale(Locale.ENGLISH);

		assertThat(translator.get("共 {0} 条", 12345)).isEqualTo("12,345 items");
	}

	@Test
	@DisplayName("没有键、没有翻译：返回 null 或者键本身，不报错")
	void missing() {
		assertThat(translator.get((String) null)).isNull();
		assertThat(translator.get("no.such.code")).isEqualTo("no.such.code");
		assertThat(translator.get("no.such.code", "x")).isEqualTo("no.such.code");
	}

	@Test
	@DisplayName("译文的占位写坏了：这次按原文返回，日志里说清是哪一条")
	void brokenTranslation(CapturedOutput output) {
		LocaleContextHolder.setLocale(Locale.ENGLISH);

		assertThat(translator.get("demo.broken", "x")).isEqualTo("demo.broken");
		assertThat(output).contains("demo.broken").contains("占位写错了");
	}

	@Test
	@DisplayName("翻译整句提示语：拿原文当键查，查不到原样返回")
	void translateWholeText() {
		assertThat(translator.translate("用户不存在")).isEqualTo("用户不存在");
		assertThat(translator.translate("用户不存在", Locale.ENGLISH)).isEqualTo("User not found");
		assertThat(translator.translate("这句没有翻译", Locale.ENGLISH)).isEqualTo("这句没有翻译");
		assertThat(translator.translate("用户不存在", null)).isEqualTo("用户不存在");

		LocaleContextHolder.setLocale(Locale.ENGLISH);

		assertThat(translator.translate("用户不存在")).isEqualTo("User not found");
	}

	@Test
	@DisplayName("整句是 {码} 的写法：按里面的码查，默认语言也查；查不到原样返回")
	void translateCode() {
		assertThat(translator.translate("{demo.required}")).isEqualTo("必填");
		assertThat(translator.translate("{demo.required}", Locale.ENGLISH)).isEqualTo("This field is required");
		assertThat(translator.translate("{no.such.code}", Locale.ENGLISH)).isEqualTo("{no.such.code}");
	}

	@Test
	@DisplayName("花括号里不是码的（JSON、带空格的句子）：当成普通的一句话")
	void bracesButNotCode() {
		for (String text : new String[] {"{}", "{\"a\":1}", "{用户}", "{demo required}", "{demo.required} 多一截"}) {
			assertThat(translator.translate(text, Locale.ENGLISH)).as(text).isEqualTo(text);
		}
	}

	@Test
	@DisplayName("空的、太长的提示语不查翻译")
	void skipsEmptyAndLong() {
		assertThat(translator.translate(null, Locale.ENGLISH)).isNull();
		assertThat(translator.translate("", Locale.ENGLISH)).isEmpty();
		assertThat(translator.translate(LONG_TEXT, Locale.ENGLISH)).isEqualTo(LONG_TEXT);
		// 写代码时主动去取的不受这个限制
		assertThat(translator.get(Locale.ENGLISH, LONG_TEXT)).isEqualTo("too long");
	}

	@Test
	@DisplayName("查文案这一步自己出了错（比如文案来源连不上数据库）：按原文返回，不影响请求；日志里提醒一次，不是每次都提醒")
	void lookupFailure(CapturedOutput output) {
		MessageSource failing = new AbstractMessageSource() {
			@Override
			protected MessageFormat resolveCode(String code, Locale locale) {
				throw new IllegalStateException("数据库连不上");
			}
		};
		MessageTranslator broken = new MessageTranslator(failing, ZH_CN);

		for (int i = 0; i < 20; i++) {
			assertThat(broken.translate("用户不存在", Locale.ENGLISH)).isEqualTo("用户不存在");
			assertThat(broken.get(Locale.ENGLISH, "用户不存在")).isEqualTo("用户不存在");
			assertThat(broken.get(Locale.ENGLISH, "用户 {0} 不存在", "tom")).isEqualTo("用户 tom 不存在");
		}

		assertThat(StringUtils.countOccurrencesOf(output.toString(), "查文案时出错")).isEqualTo(1);
		assertThat(output).contains("数据库连不上");
	}

	@Test
	@DisplayName("文案来源和默认语言都不能为空")
	void constructorChecks() {
		assertThatThrownBy(() -> new MessageTranslator(null, ZH_CN)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new MessageTranslator(new StaticMessageSource(), null))
			.isInstanceOf(IllegalArgumentException.class);
	}
}
