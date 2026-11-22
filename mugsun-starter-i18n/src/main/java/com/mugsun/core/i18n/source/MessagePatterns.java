package com.mugsun.core.i18n.source;

import java.io.Serial;
import java.text.FieldPosition;
import java.text.Format;
import java.text.MessageFormat;
import java.text.ParsePosition;
import java.util.Locale;

/**
 * 带占位的文案（{@code 用户 {0} 不存在}）怎么填值。写法是 {@link MessageFormat} 的，只有一处不同：
 * 没写格式的占位（{@code {0}}）填进去的样子，和 Java 里用加号拼字符串一样：数字不加千分位、不四舍五入，
 * 日期不按语言换格式。提示语里的数字多半是编号、行号、条数，加了逗号反而看不懂；
 * 把拼出来的句子改成带占位的写法时，显示出来的也一个字不变。
 *
 * <p>想按语言的习惯显示，就在占位里写明格式（{@code {0,number}}、{@code {0,date}}、{@code {0,choice,…}}），
 * 写明了的照 {@link MessageFormat} 的规则来（{@code choice} 各分支里嵌套的占位也是）。
 */
public final class MessagePatterns {

	private static final Format AS_IS = new AsIs();

	private MessagePatterns() {
	}

	/**
	 * 把一句带占位的文案解析好，之后调 {@link MessageFormat#format(Object)} 填值。
	 *
	 * @throws IllegalArgumentException 占位写错了（比如少了右花括号）
	 */
	public static MessageFormat compile(String pattern, Locale locale) {
		MessageFormat format = new MessageFormat(pattern, locale);
		Format[] declared = format.getFormats();
		for (int i = 0; i < declared.length; i++) {
			if (declared[i] == null) {
				format.setFormat(i, AS_IS);
			}
		}
		return format;
	}

	/** 值是什么样就显示什么样，和 {@code "" + value} 一样 */
	private static final class AsIs extends Format {

		@Serial
		private static final long serialVersionUID = 1L;

		@Override
		public StringBuffer format(Object value, StringBuffer toAppendTo, FieldPosition pos) {
			return toAppendTo.append(value);
		}

		@Override
		public Object parseObject(String source, ParsePosition pos) {
			throw new UnsupportedOperationException("只用来填值，不用来解析");
		}
	}
}
