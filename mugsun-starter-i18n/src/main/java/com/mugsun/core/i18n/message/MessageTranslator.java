package com.mugsun.core.i18n.message;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContext;
import org.springframework.context.i18n.LocaleContextHolder;

import java.text.MessageFormat;
import java.util.Locale;

/**
 * 取文案、翻译文案。应用代码一般用静态的 {@link I18n}，它背后就是这个类；需要注入时用这个 Bean。
 *
 * <p>两类方法：
 *
 * <ul>
 * <li>{@link #get(String, Object...)}：写代码时取一条文案。键可以是点分的码，也可以直接是中文原文；
 * 没有翻译时返回键本身（占位照样填好），所以用中文原文做键时，不配任何翻译文件也能正常显示中文。</li>
 * <li>{@link #translate(String)}：把一句已经拼好的提示语翻译成当前语言，给返回体出口用。
 * 整句是 {@code {user.not.found}} 这种写法时按里面的码查；其余的拿整句原文当键查；查不到原样返回。</li>
 * </ul>
 *
 * <p>两类方法都不会因为查文案这一步出错而抛异常（比如应用自己的文案来源连不上数据库）：按原文返回，日志里提醒，业务照常进行。
 */
public class MessageTranslator {

	private static final Logger log = LoggerFactory.getLogger(MessageTranslator.class);

	/** 超过这个长度的提示语不去查翻译：这么长的不会是文案的键 */
	private static final int MAX_KEY_LENGTH = 500;

	/** 查文案这一步自己出错时，隔多久才再提醒一次：出错期间每个请求都会碰到，不能每次都记 */
	private static final long LOOKUP_WARN_INTERVAL_MS = 60_000L;

	private final MessageSource messageSource;
	private final Locale defaultLocale;
	private volatile long lastLookupWarnAt;

	/**
	 * @param messageSource 文案从哪里查
	 * @param defaultLocale 默认语言：不在请求里（定时任务、启动过程）时用它
	 */
	public MessageTranslator(MessageSource messageSource, Locale defaultLocale) {
		if (messageSource == null || defaultLocale == null) {
			throw new IllegalArgumentException("文案来源和默认语言都不能为空");
		}
		this.messageSource = messageSource;
		this.defaultLocale = defaultLocale;
	}

	public Locale getDefaultLocale() {
		return defaultLocale;
	}

	/** 当前语言：在请求里是这次请求的语言，不在请求里是默认语言 */
	public Locale locale() {
		LocaleContext context = LocaleContextHolder.getLocaleContext();
		Locale locale = context != null ? context.getLocale() : null;
		return locale != null ? locale : defaultLocale;
	}

	/**
	 * 按当前语言取一条文案。
	 *
	 * @param code 点分的码（{@code user.not.found}），或者中文原文（{@code 用户 {0} 不存在}）
	 * @param args 填到 {@code {0}}、{@code {1}} 里的值。带参数时文案按 {@link MessageFormat} 的规则解析：
	 * 单引号要写两个；数字会按语言加千分位，单号这类不想加的请传字符串
	 * @return 文案；没有翻译时返回 {@code code} 本身（占位照样填好）
	 */
	public String get(String code, Object... args) {
		return get(locale(), code, args);
	}

	/** 按指定的语言取一条文案，其余和 {@link #get(String, Object...)} 一样 */
	public String get(Locale locale, String code, Object... args) {
		if (code == null) {
			return null;
		}
		Locale target = locale != null ? locale : defaultLocale;
		boolean hasArgs = args != null && args.length > 0;
		try {
			String text = messageSource.getMessage(code, hasArgs ? args : null, null, target);
			if (text != null) {
				return text;
			}
		} catch (IllegalArgumentException e) {
			log.warn("文案「{}」在语言 {} 下的翻译占位写错了，这次按原文返回：{}", code, target, e.getMessage());
		} catch (RuntimeException e) {
			warnLookupFailed(e);
		}
		return hasArgs ? format(code, args, target) : code;
	}

	/** 把一句提示语翻译成当前语言；规则见类说明 */
	public String translate(String text) {
		return translate(text, locale());
	}

	/** 把一句提示语翻译成指定的语言；查不到翻译原样返回 */
	public String translate(String text, Locale locale) {
		if (text == null || text.isEmpty() || text.length() > MAX_KEY_LENGTH) {
			return text;
		}
		Locale target = locale != null ? locale : defaultLocale;
		String code = codeOf(text);
		try {
			String translated = messageSource.getMessage(code != null ? code : text, null, null, target);
			return translated != null ? translated : text;
		} catch (RuntimeException e) {
			warnLookupFailed(e);
			return text;
		}
	}

	private void warnLookupFailed(RuntimeException e) {
		long now = System.currentTimeMillis();
		if (now - lastLookupWarnAt >= LOOKUP_WARN_INTERVAL_MS) {
			lastLookupWarnAt = now;
			log.warn("查文案时出错，这次按原文返回（一分钟内不再重复提醒）", e);
		}
	}

	private static String format(String pattern, Object[] args, Locale locale) {
		try {
			return new MessageFormat(pattern, locale).format(args);
		} catch (IllegalArgumentException e) {
			return pattern;
		}
	}

	/** 整句是 {@code {user.not.found}} 这种写法时，返回里面的码；不是返回 null */
	private static String codeOf(String text) {
		int length = text.length();
		if (length < 3 || text.charAt(0) != '{' || text.charAt(length - 1) != '}') {
			return null;
		}
		for (int i = 1; i < length - 1; i++) {
			char c = text.charAt(i);
			boolean allowed = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
				|| c == '.' || c == '_' || c == '-';
			if (!allowed) {
				return null;
			}
		}
		return text.substring(1, length - 1);
	}
}
