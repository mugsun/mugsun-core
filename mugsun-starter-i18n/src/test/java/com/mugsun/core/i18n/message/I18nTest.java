package com.mugsun.core.i18n.message;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.StaticMessageSource;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("取文案的静态入口")
class I18nTest {

	private final MessageTranslator english = translator(Locale.ENGLISH, "来自英文");

	private final MessageTranslator french = translator(Locale.FRENCH, "来自法文");

	@AfterEach
	void cleanUp() {
		I18n.uninstall(english);
		I18n.uninstall(french);
		LocaleContextHolder.resetLocaleContext();
	}

	@Test
	@DisplayName("没有装配（比如单元测试）：返回键本身，占位照样填好")
	void fallback() {
		assertThat(I18n.get("用户 {0} 不存在", "tom")).isEqualTo("用户 tom 不存在");
		assertThat(I18n.get("user.not.found")).isEqualTo("user.not.found");
		assertThat(I18n.get(Locale.ENGLISH, "用户 {0} 不存在", "tom")).isEqualTo("用户 tom 不存在");
		assertThat(I18n.translate("用户不存在")).isEqualTo("用户不存在");
		assertThat(I18n.translate(Locale.ENGLISH, "用户不存在")).isEqualTo("用户不存在");
		assertThat(I18n.locale()).isEqualTo(Locale.SIMPLIFIED_CHINESE);
		assertThat(I18n.negotiate("en-US,en;q=0.9")).isEqualTo(Locale.SIMPLIFIED_CHINESE);
		assertThat(I18n.negotiate(null)).isEqualTo(Locale.SIMPLIFIED_CHINESE);
	}

	@Test
	@DisplayName("装上以后静态方法都走它；卸掉回到没有装配的状态")
	void installAndUninstall() {
		I18n.install(english);

		assertThat(I18n.translator()).isSameAs(english);
		assertThat(I18n.get("greeting")).isEqualTo("来自英文");
		assertThat(I18n.translate("greeting")).isEqualTo("来自英文");
		assertThat(I18n.translate(Locale.FRENCH, "greeting")).isEqualTo("greeting");
		assertThat(I18n.locale()).isEqualTo(Locale.ENGLISH);
		assertThat(I18n.negotiate("fr-FR,fr;q=0.9")).isEqualTo(Locale.ENGLISH);

		I18n.uninstall(english);

		assertThat(I18n.translator()).isNotSameAs(english);
		assertThat(I18n.get("greeting")).isEqualTo("greeting");
	}

	@Test
	@DisplayName("不在请求里时 Spring 用的语言跟着默认语言走，不跟服务器操作系统走")
	void defaultLocaleOfSpring() {
		Locale system = Locale.getDefault();
		Locale.setDefault(Locale.GERMANY);
		try {
			I18n.install(french);
			assertThat(LocaleContextHolder.getLocale()).isEqualTo(Locale.FRENCH);

			I18n.uninstall(french);
			assertThat(LocaleContextHolder.getLocale()).isEqualTo(Locale.GERMANY);
		} finally {
			Locale.setDefault(system);
		}
	}

	@Test
	@DisplayName("先后装了几个（一个进程里起了几个容器）：最后装的生效，卸掉哪个都能退回还在的那个")
	void stack() {
		I18n.install(english);
		I18n.install(french);
		assertThat(I18n.translator()).isSameAs(french);

		I18n.uninstall(french);
		assertThat(I18n.translator()).isSameAs(english);
		assertThat(LocaleContextHolder.getLocale()).isEqualTo(Locale.ENGLISH);

		I18n.install(french);
		I18n.uninstall(english);
		assertThat(I18n.translator()).isSameAs(french);
		assertThat(LocaleContextHolder.getLocale()).isEqualTo(Locale.FRENCH);

		I18n.uninstall(french);
		assertThat(I18n.get("greeting")).isEqualTo("greeting");
	}

	@Test
	@DisplayName("同一个重复装不会留下两份；卸没装过的没有影响")
	void idempotent() {
		I18n.install(english);
		I18n.install(french);
		I18n.install(english);
		assertThat(I18n.translator()).isSameAs(english);

		I18n.uninstall(english);
		assertThat(I18n.translator()).isSameAs(french);

		I18n.uninstall(english);
		assertThat(I18n.translator()).isSameAs(french);
	}

	@Test
	@DisplayName("翻译器不能为空")
	void nullRejected() {
		assertThatThrownBy(() -> I18n.install(null)).isInstanceOf(IllegalArgumentException.class);
	}

	/** 只有一条文案 greeting 的翻译器，默认语言就是这条文案的语言 */
	private static MessageTranslator translator(Locale defaultLocale, String greeting) {
		StaticMessageSource source = new StaticMessageSource();
		source.addMessage("greeting", defaultLocale, greeting);
		return new MessageTranslator(source, defaultLocale);
	}
}
