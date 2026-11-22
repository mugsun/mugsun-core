package com.mugsun.core.i18n.message;

import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.StaticMessageSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 取文案的静态入口，哪里都能直接用：
 *
 * <pre>{@code
 * // 1. 用中文原文做键：不配翻译文件就显示这句中文；配了英文翻译、请求又是英文时显示英文
 * throw new ServiceException(I18n.get("用户 {0} 不存在", username));
 *
 * // 2. 用点分的码做键：中文和英文都写在翻译文件里
 * throw new ServiceException(I18n.get("user.not.found", username));
 * }</pre>
 *
 * <p>不带参数、整句固定的提示语不用改代码：照常 {@code throw new ServiceException("用户不存在")}，
 * 返回前会拿整句原文去查翻译，只要在翻译文件里加一行 {@code 用户不存在=User not found}。
 *
 * <p>没有 Spring 容器时（比如单元测试）也能用：没有翻译可查，返回键本身并把占位填好。
 */
public final class I18n {

	/** 没有装配时用的：查不到任何翻译，默认语言简体中文 */
	private static final MessageTranslator FALLBACK =
		new MessageTranslator(new StaticMessageSource(), Locale.SIMPLIFIED_CHINESE);

	/** 装配进来的，最后装的生效；一个进程里先后起了几个容器（测试里常见）时，关掉一个能退回前一个 */
	private static final List<MessageTranslator> INSTALLED = new ArrayList<>();

	private static volatile MessageTranslator translator = FALLBACK;

	private I18n() {
	}

	/**
	 * 按当前语言取一条文案。
	 *
	 * @param code 点分的码，或者中文原文
	 * @param args 填到 {@code {0}}、{@code {1}} 里的值
	 * @return 文案；没有翻译时返回 {@code code} 本身（占位照样填好）
	 * @see MessageTranslator#get(String, Object...)
	 */
	public static String get(String code, Object... args) {
		return translator.get(code, args);
	}

	/** 按指定的语言取一条文案 */
	public static String get(Locale locale, String code, Object... args) {
		return translator.get(locale, code, args);
	}

	/** 把一句已经拼好的提示语翻译成当前语言；查不到翻译原样返回。不经过统一返回体的地方（比如过滤器里自己写响应）用它 */
	public static String translate(String text) {
		return translator.translate(text);
	}

	/** 当前语言：在请求里是这次请求的语言，不在请求里是默认语言 */
	public static Locale locale() {
		return translator.locale();
	}

	/** 当前生效的翻译器 */
	public static MessageTranslator translator() {
		return translator;
	}

	/**
	 * 装上一个翻译器，之后的静态方法都走它。组件启动时自己会调，应用代码不用管。
	 *
	 * <p>同时把 Spring 的「不在请求里时用什么语言」定成翻译器的默认语言：不这样做的话，
	 * 定时任务这类代码里的校验提示会跟着服务器操作系统的语言走，换一台机器结果就变了。
	 */
	public static synchronized void install(MessageTranslator candidate) {
		if (candidate == null) {
			throw new IllegalArgumentException("翻译器不能为空");
		}
		INSTALLED.remove(candidate);
		INSTALLED.add(candidate);
		apply();
	}

	/** 卸掉一个翻译器：退回它之前装的那个，一个都不剩时回到「查不到任何翻译」的状态 */
	public static synchronized void uninstall(MessageTranslator candidate) {
		if (INSTALLED.remove(candidate)) {
			apply();
		}
	}

	private static void apply() {
		MessageTranslator current = INSTALLED.isEmpty() ? null : INSTALLED.get(INSTALLED.size() - 1);
		translator = current != null ? current : FALLBACK;
		LocaleContextHolder.setDefaultLocale(current != null ? current.getDefaultLocale() : null);
	}
}
