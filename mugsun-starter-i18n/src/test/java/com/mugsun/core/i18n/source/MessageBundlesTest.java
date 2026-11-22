package com.mugsun.core.i18n.source;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("读取并合并翻译文件")
@ExtendWith(OutputCaptureExtension.class)
class MessageBundlesTest {

	@TempDir
	Path temp;

	@Test
	@DisplayName("文件名拆成名字和语言")
	void parseName() {
		assertParsed("messages", "messages", Locale.ROOT);
		assertParsed("messages_en", "messages", Locale.ENGLISH);
		assertParsed("messages_zh_CN", "messages", Locale.SIMPLIFIED_CHINESE);
		assertParsed("messages_zh_cn", "messages", Locale.SIMPLIFIED_CHINESE);
		assertParsed("messages_zh_Hant_TW", "messages", Locale.forLanguageTag("zh-Hant-TW"));
		assertParsed("messages_es_419", "messages", Locale.forLanguageTag("es-419"));
		assertParsed("mugsun-core_en", "mugsun-core", Locale.ENGLISH);
	}

	@Test
	@DisplayName("只写语言不写名字的文件，等同于 messages_<语言>")
	void languageOnlyName() {
		assertParsed("en", "messages", Locale.ENGLISH);
		assertParsed("zh_CN", "messages", Locale.SIMPLIFIED_CHINESE);
		assertParsed("zh-CN", "messages", Locale.SIMPLIFIED_CHINESE);
	}

	@Test
	@DisplayName("不带语言的文件是兜底文案：名字里的短横线、和语言代码撞上的普通单词都不会被当成语言")
	void rootNames() {
		assertParsed("mugsun-core", "mugsun-core", Locale.ROOT);
		assertParsed("application", "application", Locale.ROOT);
		// sms 也是一种语言的三位代码：三位的不认，按名字算
		assertParsed("sms", "sms", Locale.ROOT);
	}

	@Test
	@DisplayName("文件名认不出语言：不读，并说明该怎么改名")
	void badNames() {
		// 日语是 ja，不是 jp
		assertProblem("messages_jp", "「jp」不是语言代码");
		// 名字里不能有下划线
		assertProblem("my_app_en", "「app_en」不是语言代码");
		// 地区写错了
		assertProblem("messages_en_ZZ", "「en_ZZ」不是语言代码");
		// 三位的语言代码不支持
		assertProblem("messages_eng", "「eng」不是语言代码");
		assertProblem("_en", "不能以下划线开头");
		// 把下划线写成了短横线：不能当成兜底文案读进来，否则里面的英文会发给所有语言的用户
		assertProblem("messages-en", "请改名为 messages_en.properties");
		assertProblem("mugsun-core-zh-CN", "请改名为 mugsun-core_zh_CN.properties");
	}

	@Test
	@DisplayName("配置里写明了名字的文件：只认这个名字的，开头一样的别的文件不算")
	void parseKnownName() {
		assertThat(MessageBundles.parseName("messages", "messages").locale()).isEqualTo(Locale.ROOT);
		assertThat(MessageBundles.parseName("messages_en", "messages").locale()).isEqualTo(Locale.ENGLISH);
		assertThat(MessageBundles.parseName("messages-extra_fr", "messages")).isNull();
		assertThat(MessageBundles.parseName("messagesX", "messages")).isNull();
		assertThat(MessageBundles.parseName("messages_jp", "messages").problem()).contains("「jp」不是语言代码");
		// 配置里写的名字可以带下划线
		assertThat(MessageBundles.parseName("my_texts_en", "my_texts").locale()).isEqualTo(Locale.ENGLISH);
	}

	@Test
	@DisplayName("几个 jar 里的同名文件合在一起，同一个键以类路径上排在前面的为准")
	void mergesSameNamedFiles() throws IOException {
		Path first = root("first");
		Path second = root("second");
		write(first, "i18n/messages_en.properties", "a=A\nboth=from first\n");
		write(second, "i18n/messages_en.properties", "b=B\nboth=from second\n");

		MessageBundles bundles = load(null, first, second);

		assertThat(bundles.of(Locale.ENGLISH)).containsOnly(Map.entry("a", "A"), Map.entry("b", "B"),
			Map.entry("both", "from first"));
		assertThat(bundles.fileCount()).isEqualTo(2);
	}

	@Test
	@DisplayName("名字叫 messages 的优先，哪怕它在类路径上排在后面；其它文件按名字排序")
	void appBundleFirst() throws IOException {
		Path library = root("library");
		Path app = root("app");
		write(library, "i18n/bbb-lib_en.properties", "k=bbb\nlibs=bbb\nonly-bbb=1\n");
		write(library, "i18n/aaa-lib_en.properties", "k=aaa\nlibs=aaa\n");
		write(app, "i18n/messages_en.properties", "k=app\n");

		MessageBundles bundles = load(null, library, app);

		assertThat(bundles.of(Locale.ENGLISH)).containsOnly(Map.entry("k", "app"), Map.entry("libs", "aaa"),
			Map.entry("only-bbb", "1"));
	}

	@Test
	@DisplayName("只写语言的 en.properties 也读；同一个键以写了名字的 messages_en 为准")
	void languageOnlyFile() throws IOException {
		Path app = root("app");
		write(app, "i18n/en.properties", "k=short\nonly-short=1\n");
		write(app, "i18n/messages_en.properties", "k=named\n");
		write(app, "i18n/lib_en.properties", "k=lib\nonly-short=lib\n");

		assertThat(load(null, app).of(Locale.ENGLISH)).containsOnly(Map.entry("k", "named"),
			Map.entry("only-short", "1"));
	}

	@Test
	@DisplayName("按 Spring Boot 老习惯放在根目录的 messages 也读：排在 i18n/messages 后面、其它文件前面")
	void bootBasename() throws IOException {
		Path app = root("app");
		write(app, "messages_fr.properties", "k=root\nroot-and-other=root\nonly-root=1\n");
		write(app, "messages-extra_fr.properties", "extra=1\n");
		write(app, "i18n/messages_fr.properties", "k=i18n\n");
		write(app, "i18n/other_fr.properties", "k=other\nroot-and-other=other\n");
		write(app, "com/demo/texts_de.properties", "k=package\n");

		MessageBundles bundles = load(List.of("messages", "com.demo.texts", " "), app);

		assertThat(bundles.of(Locale.FRENCH)).containsOnly(Map.entry("k", "i18n"), Map.entry("root-and-other", "root"),
			Map.entry("only-root", "1"));
		assertThat(bundles.of(Locale.GERMAN)).containsOnly(Map.entry("k", "package"));
		assertThat(bundles.locales()).containsExactly(Locale.GERMAN, Locale.FRENCH);
	}

	@Test
	@DisplayName("打成 jar 包以后照样读：i18n 目录下的、放在 jar 根目录的 messages 都读得到")
	void insideJars() throws IOException {
		Path library = jar("library.jar", "i18n/lib_en.properties", "from-lib=1\nboth=lib\n");
		Path app = jar("app.jar", "messages_en.properties", "from-app=1\nboth=app\n");

		MessageBundles bundles = load(List.of("messages"), library, app);

		assertThat(bundles.of(Locale.ENGLISH)).containsOnly(Map.entry("from-lib", "1"), Map.entry("from-app", "1"),
			Map.entry("both", "app"));
		assertThat(bundles.fileCount()).isEqualTo(2);
	}

	@Test
	@DisplayName("不带语言后缀的文件是兜底文案，不算一种语言")
	void rootBundle() throws IOException {
		Path app = root("app");
		write(app, "i18n/messages.properties", "greeting=你好\n");
		write(app, "i18n/messages_en.properties", "greeting=Hello\n");
		write(app, "i18n/messages_zh_TW.properties", "greeting=您好\n");

		MessageBundles bundles = load(null, app);

		assertThat(bundles.of(Locale.ROOT)).containsOnly(Map.entry("greeting", "你好"));
		assertThat(bundles.locales()).containsExactly(Locale.ENGLISH, Locale.TRADITIONAL_CHINESE);
		assertThat(bundles.of(Locale.JAPANESE)).isEmpty();
	}

	@Test
	@DisplayName("值为空的条目当作还没翻译，轮到后面的文件")
	void emptyValueSkipped() throws IOException {
		Path app = root("app");
		write(app, "i18n/messages_en.properties", "todo=\nblank=   \n");
		write(app, "i18n/lib_en.properties", "todo=from lib\nblank=from lib\n");

		MessageBundles bundles = load(null, app);

		assertThat(bundles.of(Locale.ENGLISH)).containsOnly(Map.entry("todo", "from lib"),
			Map.entry("blank", "from lib"));
	}

	@Test
	@DisplayName("文件名写错的不读，日志里说明是哪个文件、怎么改；其它文件照常读")
	void badFileNamesSkipped(CapturedOutput output) throws IOException {
		Path app = root("app");
		write(app, "i18n/messages_jp.properties", "k=jp\n");
		write(app, "i18n/messages-en.properties", "k=dash\n");
		write(app, "i18n/messages_en.properties", "k=good\n");

		MessageBundles bundles = load(null, app);

		assertThat(bundles.of(Locale.ENGLISH)).containsOnly(Map.entry("k", "good"));
		assertThat(bundles.of(Locale.ROOT)).isEmpty();
		assertThat(bundles.locales()).containsExactly(Locale.ENGLISH);
		assertThat(bundles.fileCount()).isEqualTo(1);
		assertThat(output).contains("messages_jp.properties").contains("「jp」不是语言代码")
			.contains("messages-en.properties").contains("请改名为 messages_en.properties");
	}

	@Test
	@DisplayName("键里可以直接写空格和冒号：一行里第一个等号左边整个是键")
	void naturalKeys() throws IOException {
		Path app = root("app");
		write(app, "i18n/messages_en.properties", String.join("\n",
			"# 注释里随便写 a b = c",
			"  ! 另一种注释 a b=c",
			"AI 渠道不存在=AI channel not found",
			"用户 {0} 不存在=User {0} does not exist",
			"提示: 余额不足=Tip: insufficient balance",
			"  前后的空格不算  =  value with = sign",
			"老写法\\ 也可以=escaped",
			"a\\=b=equals in key",
			"long=first \\",
			"  second a=b",
			"onlykey",
			""));

		assertThat(load(null, app).of(Locale.ENGLISH)).containsOnly(
			Map.entry("AI 渠道不存在", "AI channel not found"),
			Map.entry("用户 {0} 不存在", "User {0} does not exist"),
			Map.entry("提示: 余额不足", "Tip: insufficient balance"),
			Map.entry("前后的空格不算", "value with = sign"),
			Map.entry("老写法 也可以", "escaped"),
			Map.entry("a=b", "equals in key"),
			Map.entry("long", "first second a=b"));
	}

	@Test
	@DisplayName("在 Windows 上存的文件（换行是 \\r\\n）照样读")
	void windowsLineEndings() throws IOException {
		Path app = root("app");
		write(app, "i18n/messages_en.properties", "用户 不存在=No user\r\n第二 行=Second\r\n");

		assertThat(load(null, app).of(Locale.ENGLISH)).containsOnly(Map.entry("用户 不存在", "No user"),
			Map.entry("第二 行", "Second"));
	}

	@Test
	@DisplayName("文件开头有看不见的标记（有些编辑器会加）：第一个键照样对得上")
	void byteOrderMark() throws IOException {
		Path app = root("app");
		write(app, "i18n/messages_en.properties", "\uFEFF用户不存在=User not found\n");

		assertThat(load(null, app).of(Locale.ENGLISH)).containsOnly(Map.entry("用户不存在", "User not found"));
	}

	@Test
	@DisplayName("不是 UTF-8 的文件、格式写坏的文件：跳过，其它文件照常读")
	void unreadableFilesSkipped() throws IOException {
		Path app = root("app");
		Path gbk = app.resolve("i18n/aaa_en.properties");
		Files.createDirectories(gbk.getParent());
		Files.write(gbk, "用户不存在=User not found\n".getBytes(Charset.forName("GBK")));
		write(app, "i18n/bbb_en.properties", "broken=\\u12\n");
		write(app, "i18n/ccc_en.properties", "good=1\n");

		MessageBundles bundles = load(null, app);

		assertThat(bundles.of(Locale.ENGLISH)).containsOnly(Map.entry("good", "1"));
		assertThat(bundles.fileCount()).isEqualTo(1);
	}

	@Test
	@DisplayName("一个翻译文件都没有：什么都查不到，但不报错")
	void nothingFound() throws IOException {
		MessageBundles bundles = load(List.of("messages"), root("empty"));

		assertThat(bundles.locales()).isEmpty();
		assertThat(bundles.fileCount()).isZero();
		assertThat(bundles.of(Locale.ROOT)).isEmpty();
		assertThat(MessageBundles.empty().locales()).isEmpty();
	}

	private static void assertParsed(String name, String bundle, Locale locale) {
		MessageBundles.ParsedName parsed = MessageBundles.parseName(name);
		assertThat(parsed.problem()).as(name).isNull();
		assertThat(parsed.bundle()).as(name).isEqualTo(bundle);
		assertThat(parsed.locale()).as(name).isEqualTo(locale);
	}

	private static void assertProblem(String name, String expected) {
		MessageBundles.ParsedName parsed = MessageBundles.parseName(name);
		assertThat(parsed.locale()).as(name).isNull();
		assertThat(parsed.problem()).as(name).contains(expected);
	}

	private Path root(String name) throws IOException {
		return Files.createDirectories(temp.resolve(name));
	}

	private static void write(Path root, String relative, String content) throws IOException {
		Path file = root.resolve(relative);
		Files.createDirectories(file.getParent());
		Files.writeString(file, content, StandardCharsets.UTF_8);
	}

	/** 打一个真的 jar 包，里面只有一个文件；和 Maven 打出来的一样带目录条目 */
	private Path jar(String name, String entry, String content) throws IOException {
		Path jar = temp.resolve(name);
		try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
			Set<String> directories = new HashSet<>();
			for (int slash = entry.indexOf('/'); slash > 0; slash = entry.indexOf('/', slash + 1)) {
				if (directories.add(entry.substring(0, slash + 1))) {
					out.putNextEntry(new JarEntry(entry.substring(0, slash + 1)));
					out.closeEntry();
				}
			}
			out.putNextEntry(new JarEntry(entry));
			out.write(content.getBytes(StandardCharsets.UTF_8));
			out.closeEntry();
		}
		return jar;
	}

	/** 把几个目录（或者 jar 包）当成类路径上先后排列的几个 jar，不带测试自己的类路径 */
	private static MessageBundles load(List<String> basenames, Path... roots) throws IOException {
		URL[] urls = new URL[roots.length];
		for (int i = 0; i < roots.length; i++) {
			urls[i] = roots[i].toUri().toURL();
		}
		try (URLClassLoader loader = new URLClassLoader(urls, null)) {
			return MessageBundles.load(new PathMatchingResourcePatternResolver(loader), basenames);
		}
	}
}
