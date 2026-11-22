package com.mugsun.core.i18n.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 多语言的配置。全部有默认值，一项都不配也能用：
 *
 * <pre>
 * mugsun:
 *   i18n:
 *     default-locale: zh-CN     # 请求没说要什么语言、或者要的语言不支持时用它
 *     supported-locales: en     # 不配 = 默认语言 + 简体中文 + 有翻译文件的语言
 *     translate-response: true  # 返回前把提示语翻译成请求的语言
 * </pre>
 */
@ConfigurationProperties(prefix = "mugsun.i18n")
public class I18nProperties {

	/** 是否启用多语言组件。关掉以后不注册任何东西，提示语原样返回。 */
	private boolean enabled = true;

	/** 默认语言：请求没带 Accept-Language、带的语言都不支持、或者代码不在请求里（定时任务）时用它。默认简体中文。 */
	private Locale defaultLocale = Locale.SIMPLIFIED_CHINESE;

	/**
	 * 除默认语言外还支持哪些语言。不配时自动取：简体中文（代码里的提示语本来就是简体中文）加上有翻译文件的语言；
	 * 配了就只认默认语言和配的这几种。
	 */
	private List<Locale> supportedLocales = new ArrayList<>();

	/** 是否在返回前把统一返回体里的提示语翻译成请求的语言。 */
	private boolean translateResponse = true;

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public Locale getDefaultLocale() {
		return defaultLocale;
	}

	public void setDefaultLocale(Locale defaultLocale) {
		this.defaultLocale = defaultLocale;
	}

	public List<Locale> getSupportedLocales() {
		return supportedLocales;
	}

	public void setSupportedLocales(List<Locale> supportedLocales) {
		this.supportedLocales = supportedLocales;
	}

	public boolean isTranslateResponse() {
		return translateResponse;
	}

	public void setTranslateResponse(boolean translateResponse) {
		this.translateResponse = translateResponse;
	}
}
