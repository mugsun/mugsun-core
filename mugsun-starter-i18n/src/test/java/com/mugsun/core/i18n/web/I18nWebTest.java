package com.mugsun.core.i18n.web;

import com.jayway.jsonpath.JsonPath;
import com.mugsun.core.i18n.config.MugsunI18nAutoConfiguration;
import com.mugsun.core.i18n.message.I18n;
import com.mugsun.core.tool.api.R;
import com.mugsun.core.tool.api.ResultCode;
import com.mugsun.core.tool.exception.ForbiddenException;
import com.mugsun.core.tool.exception.ServiceException;
import com.mugsun.core.web.config.MugsunWebAutoConfiguration;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.MessageSourceAutoConfiguration;
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.jackson2.autoconfigure.Jackson2AutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 和统一异常处理（mugsun-starter-web）、参数校验一起按自动装配加载，走真的 Spring MVC 处理链：
 * 翻译文件在 src/test/resources 下，请求头里带什么语言，返回的提示语就是什么语言。
 */
@DisplayName("真发请求：返回的提示语跟着请求头里的语言走")
class I18nWebTest {

	private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(DispatcherServletAutoConfiguration.class, WebMvcAutoConfiguration.class,
			HttpMessageConvertersAutoConfiguration.class, Jackson2AutoConfiguration.class,
			ValidationAutoConfiguration.class, MessageSourceAutoConfiguration.class,
			MugsunWebAutoConfiguration.class, MugsunI18nAutoConfiguration.class))
		// 统一异常处理那个模块没配密钥时会自动生成密钥文件：指到临时目录，不碰 ~/.mugsun
		.withPropertyValues("mugsun.home=" + System.getProperty("java.io.tmpdir") + "/mugsun-test-home-"
			+ ProcessHandle.current().pid())
		.withUserConfiguration(DemoConfig.class);

	@Test
	@DisplayName("控制器直接返回的提示语：英文请求给英文，不带语言的请求还是中文；数据不动")
	void controllerResult() {
		run(mvc -> {
			assertThat(msg(mvc, get("/ok"), null)).isEqualTo("操作成功");
			assertThat(msg(mvc, get("/ok"), "zh-CN,zh;q=0.9")).isEqualTo("操作成功");
			// 应用自己的 messages_en 里写的是 OK，比库自带的翻译优先
			assertThat(msg(mvc, get("/ok"), "en")).isEqualTo("OK");
			assertThat(msg(mvc, get("/ok"), "en-US,en;q=0.9,zh-CN;q=0.8")).isEqualTo("OK");
			// 排在前面的语言不支持：用后面第一个支持的
			assertThat(msg(mvc, get("/ok"), "fr-FR,fr;q=0.9,en;q=0.8")).isEqualTo("OK");

			// 只换提示语：data 里的内容恰好是一句有翻译的话也不动，别的字段和中文请求的一模一样
			assertThat(data(mvc, get("/ok"), "en")).isEqualTo("用户不存在");
			assertThat(body(mvc, get("/ok"), "en")).isEqualTo(body(mvc, get("/ok"), null).replace("操作成功", "OK"));
		});
	}

	@Test
	@DisplayName("业务异常里写死的中文提示：代码不用改，翻译文件里有这句就换掉，没有就原样返回")
	void serviceException() {
		run(mvc -> {
			assertThat(msg(mvc, get("/missing-user"), null)).isEqualTo("用户不存在");
			assertThat(msg(mvc, get("/missing-user"), "en")).isEqualTo("User not found");
			// 带地区的翻译文件 messages_zh_TW
			assertThat(msg(mvc, get("/missing-user"), "zh-TW")).isEqualTo("使用者不存在");
			// 按 Spring Boot 老习惯放在类路径根目录下的 messages_ja
			assertThat(msg(mvc, get("/missing-user"), "ja")).isEqualTo("ユーザーが存在しません");
			// 不支持的语言：用默认语言
			assertThat(msg(mvc, get("/missing-user"), "fr-FR,fr;q=0.9")).isEqualTo("用户不存在");
			// 没有翻译的句子：原样返回
			assertThat(msg(mvc, get("/untranslated"), "en")).isEqualTo("这句没有翻译");

			// 只换提示语，返回体里别的字段和中文请求的一模一样
			assertThat(body(mvc, get("/missing-user"), "en"))
				.isEqualTo(body(mvc, get("/missing-user"), null).replace("用户不存在", "User not found"));
		});
	}

	@Test
	@DisplayName("带参数的提示用 I18n.get：中文原文或者码做键，占位在两种语言里都填好")
	void staticEntry() {
		run(mvc -> {
			assertThat(msg(mvc, get("/missing-user/{name}", "tom"), null)).isEqualTo("用户 tom 不存在");
			assertThat(msg(mvc, get("/missing-user/{name}", "tom"), "en")).isEqualTo("User tom does not exist");
			assertThat(msg(mvc, get("/greeting/{name}", "小明"), null)).isEqualTo("你好，小明");
			assertThat(msg(mvc, get("/greeting/{name}", "Tom"), "en")).isEqualTo("Hello, Tom");
		});
	}

	@Test
	@DisplayName("提示语写成 {码}：按码查，中文也从翻译文件里取")
	void codeMessage() {
		run(mvc -> {
			assertThat(msg(mvc, get("/code"), null)).isEqualTo("必填");
			assertThat(msg(mvc, get("/code"), "en")).isEqualTo("This field is required");
		});
	}

	@Test
	@DisplayName("校验注解里的提示语：写死的中文、{码}、校验框架自带的，三种都跟着请求的语言走")
	void validation() {
		run(mvc -> {
			assertThat(msg(mvc, emptyJson("/form/text"), null)).isEqualTo("名称不能为空");
			assertThat(msg(mvc, emptyJson("/form/text"), "en")).isEqualTo("Name is required");

			assertThat(msg(mvc, emptyJson("/form/code"), null)).isEqualTo("必填");
			assertThat(msg(mvc, emptyJson("/form/code"), "en")).isEqualTo("This field is required");

			assertThat(msg(mvc, emptyJson("/form/builtin"), "en")).isEqualTo("must not be null");
			// 校验框架自带的中文说法不归这里管，只看它是中文
			assertThat(msg(mvc, emptyJson("/form/builtin"), null)).isNotEqualTo("must not be null")
				.containsPattern("[\\u4e00-\\u9fa5]");

			// 状态码还是参数错误的 400，翻译不改它
			mvc.perform(emptyJson("/form/text").header(HttpHeaders.ACCEPT_LANGUAGE, "en"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.success").value(false));
		});
	}

	@Test
	@DisplayName("框架自己返回的提示语带了英文翻译：没有这个地址、没有权限、请求方法不对")
	void frameworkMessages() {
		run(mvc -> {
			assertThat(msg(mvc, get("/no-such-page"), null)).isEqualTo("资源不存在");
			assertThat(msg(mvc, get("/no-such-page"), "en")).isEqualTo("Resource not found");
			assertThat(msg(mvc, get("/forbidden"), "en")).isEqualTo("Access denied");
			assertThat(msg(mvc, post("/ok"), "en")).isEqualTo("Request method not allowed");
			// 同一个键有两个库都带了翻译：按文件的名字排序，demo-lib 排在 mugsun-core 前面
			assertThat(msg(mvc, get("/failure"), "en")).isEqualTo("Failed (lib)");
		});
	}

	@Test
	@DisplayName("不是统一返回体的返回值不处理")
	void otherBodiesUntouched() {
		run(mvc -> {
			// demo.required 在翻译文件里有，但这里返回的是一个普通字符串，不是统一返回体
			assertThat(body(mvc, get("/text"), "en")).isEqualTo("demo.required");
			assertThat(msg(mvc, get("/map"), "en")).isEqualTo("用户不存在");
		});
	}

	@Test
	@DisplayName("换提示语时不动原来的对象：共用的返回对象先被英文请求用过，中文请求拿到的还是中文")
	void sharedResultNotMutated() {
		run(mvc -> {
			assertThat(msg(mvc, get("/shared"), "en")).isEqualTo("User not found");
			assertThat(msg(mvc, get("/shared"), null)).isEqualTo("用户不存在");
			assertThat(DemoController.SHARED.getMsg()).isEqualTo("用户不存在");
		});
	}

	@Test
	@DisplayName("统一返回体的子类：多出来的字段不丢，提示语照样翻译")
	void subclass() {
		run(mvc -> mvc.perform(get("/page").header(HttpHeaders.ACCEPT_LANGUAGE, "en"))
			.andExpect(jsonPath("$.msg").value("OK"))
			.andExpect(jsonPath("$.data").value("rows"))
			.andExpect(jsonPath("$.total").value(42)));
	}

	@Test
	@DisplayName("请求里取到的当前语言一定是支持的语言之一，Spring 自己认的也是这个")
	void currentLocale() {
		run(mvc -> {
			assertThat(data(mvc, get("/locale"), null)).isEqualTo("zh-CN|zh-CN");
			assertThat(data(mvc, get("/locale"), "en-GB,en;q=0.9")).isEqualTo("en|en");
			assertThat(data(mvc, get("/locale"), "zh-TW")).isEqualTo("zh-TW|zh-TW");
			// 没有香港的翻译：同一种语言里挑排在前面的，也就是默认的简体中文
			assertThat(data(mvc, get("/locale"), "zh-HK")).isEqualTo("zh-CN|zh-CN");
			assertThat(data(mvc, get("/locale"), "ko")).isEqualTo("zh-CN|zh-CN");
			assertThat(data(mvc, get("/locale"), "@@@")).isEqualTo("zh-CN|zh-CN");
		});
	}

	@Test
	@DisplayName("关掉返回前的翻译：写死的提示语原样返回，用 I18n 取的文案仍然按请求的语言")
	void translateResponseOff() {
		runner.withPropertyValues("mugsun.i18n.translate-response=false").run(context -> {
			MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();

			assertThat(msg(mvc, get("/missing-user"), "en")).isEqualTo("用户不存在");
			assertThat(msg(mvc, get("/missing-user/{name}", "tom"), "en")).isEqualTo("User tom does not exist");
		});
	}

	@Test
	@DisplayName("整个组件关掉：提示语原样返回，I18n 返回键本身")
	void disabled() {
		runner.withPropertyValues("mugsun.i18n.enabled=false").run(context -> {
			MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();

			assertThat(msg(mvc, get("/missing-user"), "en")).isEqualTo("用户不存在");
			assertThat(msg(mvc, get("/missing-user/{name}", "tom"), "en")).isEqualTo("用户 tom 不存在");
		});
	}

	@Test
	@DisplayName("默认语言改成英文：不带语言的请求、语言不支持的请求都给英文；中文请求还是中文")
	void defaultLocaleChanged() {
		runner.withPropertyValues("mugsun.i18n.default-locale=en").run(context -> {
			MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();

			assertThat(msg(mvc, get("/missing-user"), null)).isEqualTo("User not found");
			assertThat(msg(mvc, get("/missing-user"), "fr")).isEqualTo("User not found");
			assertThat(msg(mvc, get("/missing-user"), "ja")).isEqualTo("ユーザーが存在しません");
			// 代码里写的提示语本来就是简体中文，不需要翻译文件
			assertThat(msg(mvc, get("/missing-user"), "zh-CN,zh;q=0.9")).isEqualTo("用户不存在");
		});
	}

	@Test
	@DisplayName("应用用 spring.web.locale-resolver=fixed 把语言定死：不再看请求头，提示语按定死的语言翻译")
	void fixedLocale() {
		runner.withPropertyValues("spring.web.locale-resolver=fixed", "spring.web.locale=en").run(context -> {
			MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();

			assertThat(msg(mvc, get("/missing-user"), null)).isEqualTo("User not found");
			assertThat(msg(mvc, get("/missing-user"), "zh-CN")).isEqualTo("User not found");
			assertThat(data(mvc, get("/locale"), "zh-CN")).isEqualTo("en|en");
		});
	}

	private void run(MvcConsumer consumer) {
		runner.run(context -> {
			assertThat(context).hasNotFailed();
			consumer.accept(MockMvcBuilders.webAppContextSetup(context).build());
		});
	}

	private static MockHttpServletRequestBuilder emptyJson(String path) {
		return post(path).contentType(MediaType.APPLICATION_JSON).content("{}");
	}

	private static String msg(MockMvc mvc, MockHttpServletRequestBuilder request, String acceptLanguage)
			throws Exception {
		return JsonPath.read(body(mvc, request, acceptLanguage), "$.msg");
	}

	private static String data(MockMvc mvc, MockHttpServletRequestBuilder request, String acceptLanguage)
			throws Exception {
		return JsonPath.read(body(mvc, request, acceptLanguage), "$.data");
	}

	/** @param acceptLanguage 请求头 Accept-Language 的值；传 null 表示不带这个头 */
	private static String body(MockMvc mvc, MockHttpServletRequestBuilder request, String acceptLanguage)
			throws Exception {
		if (acceptLanguage != null) {
			request.header(HttpHeaders.ACCEPT_LANGUAGE, acceptLanguage);
		}
		return mvc.perform(request).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
	}

	@FunctionalInterface
	private interface MvcConsumer {

		void accept(MockMvc mvc) throws Exception;
	}

	@Configuration(proxyBeanMethods = false)
	static class DemoConfig {

		@Bean
		DemoController demoController() {
			return new DemoController();
		}
	}

	@RestController
	static class DemoController {

		/** 共用的返回对象：翻译时不能就地改它 */
		static final R<Void> SHARED = R.fail("用户不存在");

		@GetMapping("/ok")
		R<String> ok() {
			return R.data("用户不存在");
		}

		@GetMapping("/failure")
		R<Void> failure() {
			return R.fail(ResultCode.FAILURE);
		}

		@GetMapping("/missing-user")
		R<Void> missingUser() {
			throw new ServiceException("用户不存在");
		}

		@GetMapping("/missing-user/{name}")
		R<Void> missingUserNamed(@PathVariable("name") String name) {
			throw new ServiceException(I18n.get("用户 {0} 不存在", name));
		}

		@GetMapping("/greeting/{name}")
		R<Void> greeting(@PathVariable("name") String name) {
			return R.success(I18n.get("demo.greeting", name));
		}

		@GetMapping("/untranslated")
		R<Void> untranslated() {
			return R.fail("这句没有翻译");
		}

		@GetMapping("/code")
		R<Void> code() {
			return R.fail("{demo.required}");
		}

		@GetMapping("/forbidden")
		R<Void> forbidden() {
			throw new ForbiddenException("没有访问权限");
		}

		@GetMapping("/shared")
		R<Void> shared() {
			return SHARED;
		}

		@GetMapping("/page")
		PageResult page() {
			return new PageResult();
		}

		@GetMapping("/text")
		String text() {
			return "demo.required";
		}

		@GetMapping("/map")
		Map<String, String> map() {
			return Map.of("msg", "用户不存在");
		}

		@GetMapping("/locale")
		R<String> locale() {
			return R.data(I18n.locale().toLanguageTag() + "|" + LocaleContextHolder.getLocale().toLanguageTag());
		}

		@PostMapping("/form/text")
		R<Void> textForm(@Valid @RequestBody TextForm form) {
			return R.success("操作成功");
		}

		@PostMapping("/form/code")
		R<Void> codeForm(@Valid @RequestBody CodeForm form) {
			return R.success("操作成功");
		}

		@PostMapping("/form/builtin")
		R<Void> builtinForm(@Valid @RequestBody BuiltinForm form) {
			return R.success("操作成功");
		}
	}

	/** 统一返回体的子类，多一个字段 */
	static class PageResult extends R<String> {

		PageResult() {
			setCode(ResultCode.SUCCESS.getCode());
			setSuccess(true);
			setMsg(ResultCode.SUCCESS.getMsg());
			setData("rows");
		}

		public long getTotal() {
			return 42;
		}
	}

	/** 提示语是写死的中文 */
	static class TextForm {

		@NotBlank(message = "名称不能为空")
		private String name;

		public String getName() {
			return name;
		}

		public void setName(String name) {
			this.name = name;
		}
	}

	/** 提示语是 {码} */
	static class CodeForm {

		@NotBlank(message = "{demo.required}")
		private String name;

		public String getName() {
			return name;
		}

		public void setName(String name) {
			this.name = name;
		}
	}

	/** 不写提示语，用校验框架自带的 */
	static class BuiltinForm {

		@NotNull
		private String name;

		public String getName() {
			return name;
		}

		public void setName(String name) {
			this.name = name;
		}
	}
}
