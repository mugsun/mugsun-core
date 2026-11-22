package com.mugsun.core.i18n.source;

import java.util.Locale;

/**
 * 翻译文件之外的文案来源，比如存在数据库里、可以在线修改的文案。注册成 Bean 就会被用到，排在翻译文件前面：
 * 这里找到了就用这里的，返回 null 再去翻译文件里找。有多个时按 Bean 的顺序（{@code @Order}）依次问。
 *
 * <p>每取一次文案都会调用，实现里要自己做缓存，不要每次都查库。抛出的异常不会影响请求：记一条日志，然后当作没找到。
 *
 * <p>只在这里有、翻译文件里没有的语言，要在 {@code mugsun.i18n.supported-locales} 里登记，
 * 否则请求这种语言时会被当成不支持、改用默认语言。
 */
@FunctionalInterface
public interface MessageProvider {

	/**
	 * @param code 文案的键：点分的码，或者文案原文
	 * @param locale 要的语言。要不要退到更笼统的语言（比如 en_US 没有就用 en）由实现自己决定，
	 * 可以用 {@link com.mugsun.core.i18n.locale.LocaleChain#of(Locale)} 拿到查找顺序
	 * @return 文案；没有返回 null
	 */
	String find(String code, Locale locale);
}
