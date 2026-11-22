package com.mugsun.core.i18n.source;

import com.mugsun.core.tool.api.ResultCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("框架自带的英文翻译")
class BuiltInTranslationsTest {

	@Test
	@DisplayName("统一返回码的每一句提示语都有英文翻译：以后加了返回码忘了翻译，这里会报出来")
	void everyResultCodeTranslated() throws IOException {
		Map<String, String> english = builtIn().of(Locale.ENGLISH);

		for (ResultCode code : ResultCode.values()) {
			assertThat(english).as(code.name()).containsKey(code.getMsg());
		}
	}

	@Test
	@DisplayName("自带的翻译只有英文一份，每一句都翻译了，译文里没有夹着中文")
	void onlyEnglish() throws IOException {
		MessageBundles bundles = builtIn();

		assertThat(bundles.fileCount()).isEqualTo(1);
		assertThat(bundles.locales()).containsExactly(Locale.ENGLISH);
		assertThat(bundles.of(Locale.ROOT)).isEmpty();
		assertThat(bundles.of(Locale.ENGLISH)).isNotEmpty().allSatisfy((key, value) -> {
			assertThat(key).as("键是代码里的中文原文").containsPattern("[\\u4e00-\\u9fa5]");
			assertThat(value).as(key).isNotBlank().doesNotContainPattern("[\\u4e00-\\u9fa5]");
		});
	}

	/** 只读本模块自己带的翻译文件，不带测试用的那些 */
	private static MessageBundles builtIn() throws IOException {
		URL classes = MessageBundles.class.getProtectionDomain().getCodeSource().getLocation();
		try (URLClassLoader loader = new URLClassLoader(new URL[] {classes}, null)) {
			return MessageBundles.load(new PathMatchingResourcePatternResolver(loader), List.of("messages"));
		}
	}
}
