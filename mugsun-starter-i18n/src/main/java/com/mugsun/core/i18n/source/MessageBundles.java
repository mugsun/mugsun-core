package com.mugsun.core.i18n.source;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IllformedLocaleException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

/**
 * 从类路径把翻译文件读进来，按语言合并成一张表。约定：
 *
 * <ul>
 * <li>文件放在类路径的 {@code i18n/} 目录下，UTF-8 编码，直接写中文，不用转码。</li>
 * <li>文件名是 {@code <名字>_<语言>.properties}：第一个下划线前面是名字，后面是语言，比如
 * {@code messages_en.properties}、{@code messages_zh_TW.properties}。语言用两位的语言代码，后面可以带地区
 * （{@code zh_CN}）或书写系统（{@code zh_Hant}）。名字里不能有下划线，要分隔就用短横线。</li>
 * <li>不带语言的 {@code <名字>.properties} 是不分语言的兜底文案；只写语言不写名字的（{@code en.properties}）
 * 等同于 {@code messages_en.properties}。</li>
 * <li>文件名认不出语言的（比如把日语写成 {@code messages_jp}、把下划线写成短横线的 {@code messages-en}）不读，
 * 打一条 WARN 说明哪里不对：宁可不生效，也不把它当成兜底文案发给所有语言的用户。</li>
 * <li>文件内容一行一条 {@code 键=译文}：第一个等号左边是键，键里可以直接写空格和冒号；键里的等号写成 {@code \=}。
 * 其余和标准的 properties 一样（{@code #} 开头是注释，行尾反斜杠续行）。</li>
 * <li>所有 jar 里的 {@code i18n/} 目录都会读，同名文件不会互相覆盖，而是合在一起。</li>
 * <li>同一个键在多处出现时：名字叫 {@code messages} 的文件（应用自己的）优先，其次是其它名字的文件（按名字排序）；
 * 名字相同的，在类路径上排在前面的优先（应用自己的代码排在它依赖的 jar 前面）。</li>
 * <li>值为空的条目当作还没翻译，不生效。</li>
 * <li>读不了的文件（不是 UTF-8、格式写坏了）打一条 WARN 后跳过，不影响启动。</li>
 * </ul>
 */
public final class MessageBundles {

	private static final Logger log = LoggerFactory.getLogger(MessageBundles.class);

	/** 约定的位置：类路径上所有 i18n 目录里的 properties 文件 */
	public static final String CONVENTION_PATTERN = "classpath*:i18n/*.properties";

	/** 应用自己的翻译文件用这个名字，比其它名字的文件优先 */
	public static final String APP_BUNDLE = "messages";

	private static final String SUFFIX = ".properties";

	private static final String NAMING_HINT = "翻译文件要叫「名字_语言.properties」，语言用两位代码，"
		+ "比如 messages_en、messages_zh_CN、messages_ja";

	/** 两位的语言代码。三位的暂不支持：它们和普通单词太容易撞（比如 sms 也是一种语言的代码） */
	private static final Set<String> ISO_LANGUAGES = new HashSet<>(Arrays.asList(Locale.getISOLanguages()));

	private static final Set<String> ISO_COUNTRIES = new HashSet<>(Arrays.asList(Locale.getISOCountries()));

	private final Map<Locale, Map<String, String>> messages;
	private final Set<Locale> locales;
	private final int fileCount;

	private MessageBundles(Map<Locale, Map<String, String>> merged, int fileCount) {
		Map<Locale, Map<String, String>> copy = new HashMap<>();
		TreeSet<Locale> found = new TreeSet<>(Comparator.comparing(Locale::toLanguageTag));
		merged.forEach((locale, texts) -> {
			copy.put(locale, Map.copyOf(texts));
			if (!Locale.ROOT.equals(locale)) {
				found.add(locale);
			}
		});
		this.messages = Map.copyOf(copy);
		this.locales = Collections.unmodifiableSet(found);
		this.fileCount = fileCount;
	}

	/** 一个文件都没有 */
	public static MessageBundles empty() {
		return new MessageBundles(Map.of(), 0);
	}

	/** 直接用现成的表（测试、或者文案不在文件里的场合）。不分语言的兜底文案用 {@link Locale#ROOT} 做键 */
	public static MessageBundles of(Map<Locale, Map<String, String>> messages) {
		return new MessageBundles(messages, 0);
	}

	/**
	 * @param resolver 用来在类路径上找文件
	 * @param basenames 约定目录以外还要读的文件，写法和 Spring Boot 的 {@code spring.messages.basename} 一样
	 * （比如 {@code messages} 就是类路径根目录下的 messages.properties、messages_en.properties……）；
	 * 这些文件和 {@code i18n/messages} 一样算应用自己的，排在 {@code i18n/messages} 后面、其它文件前面，
	 * 相互之间按写的先后。可以为 null
	 */
	public static MessageBundles load(ResourcePatternResolver resolver, List<String> basenames) {
		List<Source> sources = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		Set<String> reported = new HashSet<>();

		List<Found> convention = scan(resolver, CONVENTION_PATTERN, null, reported);
		for (Found found : convention) {
			if (APP_BUNDLE.equals(found.name().bundle())) {
				// 写了名字的 messages_en 排在只写语言的 en 前面，两个都有时结果是固定的
				add(sources, seen, found, 0, found.named() ? 0 : 1);
			}
		}
		if (basenames != null) {
			int rank = 2;
			for (String basename : basenames) {
				String path = toPath(basename);
				String bundle = path.substring(path.lastIndexOf('/') + 1);
				if (bundle.isEmpty()) {
					continue;
				}
				for (Found found : scan(resolver, "classpath*:" + path + "*" + SUFFIX, bundle, reported)) {
					add(sources, seen, found, 0, rank);
				}
				rank++;
			}
		}
		for (Found found : convention) {
			add(sources, seen, found, 1, 0);
		}
		sources.sort(Comparator.comparingInt(Source::group).thenComparingInt(Source::rank)
			.thenComparing(Source::bundle).thenComparingInt(Source::index));

		Map<Locale, Map<String, String>> merged = new LinkedHashMap<>();
		int files = 0;
		for (Source source : sources) {
			Properties properties = read(source.resource());
			if (properties == null) {
				continue;
			}
			files++;
			Map<String, String> texts = merged.computeIfAbsent(source.locale(), locale -> new HashMap<>());
			for (String key : properties.stringPropertyNames()) {
				String value = properties.getProperty(key);
				if (value != null && !value.isEmpty()) {
					texts.putIfAbsent(key, value);
				}
			}
		}
		MessageBundles bundles = new MessageBundles(merged, files);
		log.debug("多语言：读到 {} 个翻译文件，有翻译的语言：{}", files, bundles.locales());
		return bundles;
	}

	/** 某种语言的文案（只是这一级的，不含往上找的）；没有返回空表。不分语言的兜底文案用 {@link Locale#ROOT} 取 */
	public Map<String, String> of(Locale locale) {
		return messages.getOrDefault(locale, Map.of());
	}

	/** 有翻译文件的语言，不含不分语言的兜底文件 */
	public Set<Locale> locales() {
		return locales;
	}

	/** 读进来的文件个数 */
	public int fileCount() {
		return fileCount;
	}

	/**
	 * 按通配写法找文件，并把文件名拆好。文件名有问题的在这里提醒一次，不返回。
	 *
	 * @param knownBundle 已经知道名字的（配置里写明的文件）传名字；约定目录里的文件名字要从文件名里拆，传 null
	 */
	private static List<Found> scan(ResourcePatternResolver resolver, String pattern, String knownBundle,
			Set<String> reported) {
		Resource[] resources;
		try {
			resources = resolver.getResources(pattern);
		} catch (IOException e) {
			log.warn("扫描翻译文件失败，已跳过：{}，原因：{}", pattern, e.getMessage());
			return List.of();
		}
		List<Found> found = new ArrayList<>();
		for (Resource resource : resources) {
			String filename = resource.getFilename();
			if (filename == null || !filename.endsWith(SUFFIX) || filename.length() == SUFFIX.length()
					|| !resource.isReadable()) {
				continue;
			}
			String name = filename.substring(0, filename.length() - SUFFIX.length());
			ParsedName parsed = knownBundle == null ? parseName(name) : parseName(name, knownBundle);
			if (parsed == null) {
				continue;
			}
			String identity = identity(resource);
			if (parsed.problem() != null) {
				if (reported.add(identity)) {
					log.warn("翻译文件 {} 没有读：{}", resource.getDescription(), parsed.problem());
				}
				continue;
			}
			found.add(new Found(identity, resource, parsed, name.startsWith(parsed.bundle())));
		}
		return found;
	}

	private static void add(List<Source> sources, Set<String> seen, Found found, int group, int rank) {
		if (seen.add(found.identity())) {
			sources.add(new Source(group, rank, found.name().bundle(), found.name().locale(), found.resource(),
				sources.size()));
		}
	}

	private static String identity(Resource resource) {
		try {
			return resource.getURL().toString();
		} catch (IOException e) {
			return resource.getDescription();
		}
	}

	/** spring.messages.basename 的写法：可以用点分的包名，也可以用斜杠；转成类路径下的路径 */
	private static String toPath(String basename) {
		if (basename == null) {
			return "";
		}
		String path = basename.trim();
		if (path.startsWith("classpath*:")) {
			path = path.substring("classpath*:".length());
		} else if (path.startsWith("classpath:")) {
			path = path.substring("classpath:".length());
		}
		path = path.replace('.', '/');
		while (path.startsWith("/")) {
			path = path.substring(1);
		}
		return path;
	}

	/**
	 * 把约定目录里的文件名（不含 .properties）拆成名字和语言：
	 *
	 * <ul>
	 * <li>整个文件名就是一种语言（{@code en}、{@code zh_CN}）：应用自己的翻译，名字算 {@code messages}。</li>
	 * <li>有下划线：第一个下划线前面是名字，后面必须是语言；不是语言的算文件名写错了。</li>
	 * <li>没有下划线：不分语言的兜底文件。但短横线后面是语言的（{@code messages-en}）多半是把下划线写成了短横线，
	 * 也算写错了：当成兜底文件读进来的话，里面的英文会发给所有语言的用户。</li>
	 * </ul>
	 *
	 * @return 拆出来的结果；文件名写错了的，{@link ParsedName#problem()} 里是错在哪里
	 */
	static ParsedName parseName(String name) {
		Locale whole = toLocale(name);
		if (whole != null) {
			return new ParsedName(APP_BUNDLE, whole, null);
		}
		int cut = name.indexOf('_');
		if (cut == 0) {
			return new ParsedName(name, null, "文件名不能以下划线开头。" + NAMING_HINT);
		}
		if (cut > 0) {
			String suffix = name.substring(cut + 1);
			Locale locale = toLocale(suffix);
			if (locale == null) {
				return new ParsedName(name, null, notLanguage(suffix));
			}
			return new ParsedName(name.substring(0, cut), locale, null);
		}
		for (int dash = name.indexOf('-'); dash >= 0; dash = name.indexOf('-', dash + 1)) {
			String tail = name.substring(dash + 1);
			if (dash > 0 && toLocale(tail) != null) {
				return new ParsedName(name, null, "名字和语言之间要用下划线，不能用短横线：请改名为 "
					+ name.substring(0, dash) + "_" + tail.replace('-', '_') + SUFFIX + "。如果「-" + tail
					+ "」不是语言而是名字的一部分，请换个名字，免得和语言混淆");
			}
		}
		return new ParsedName(name, Locale.ROOT, null);
	}

	/**
	 * 拆配置里写明了名字的文件：只认 {@code <名字>.properties} 和 {@code <名字>_<语言>.properties}。
	 *
	 * @return 不是这个名字的文件（只是开头一样）返回 null
	 */
	static ParsedName parseName(String name, String bundle) {
		if (name.equals(bundle)) {
			return new ParsedName(bundle, Locale.ROOT, null);
		}
		if (!name.startsWith(bundle + "_")) {
			return null;
		}
		String suffix = name.substring(bundle.length() + 1);
		Locale locale = toLocale(suffix);
		if (locale == null) {
			return new ParsedName(name, null, notLanguage(suffix));
		}
		return new ParsedName(bundle, locale, null);
	}

	private static String notLanguage(String suffix) {
		return "文件名里下划线后面的「" + suffix + "」不是语言代码。" + NAMING_HINT + "；名字里要分隔请用短横线，不要用下划线";
	}

	/** 语言的写法：两位语言代码，后面可以带书写系统和地区，下划线或短横线分隔；不是的返回 null */
	private static Locale toLocale(String text) {
		if (text.isEmpty()) {
			return null;
		}
		Locale locale;
		try {
			locale = new Locale.Builder().setLanguageTag(text.replace('_', '-')).build();
		} catch (IllformedLocaleException e) {
			return null;
		}
		String language = locale.getLanguage();
		if (language.length() != 2 || !ISO_LANGUAGES.contains(language)) {
			return null;
		}
		String country = locale.getCountry();
		if (!country.isEmpty() && !ISO_COUNTRIES.contains(country) && !country.chars().allMatch(Character::isDigit)) {
			return null;
		}
		if (!locale.getVariant().isEmpty() || locale.hasExtensions()) {
			return null;
		}
		return locale;
	}

	private static Properties read(Resource resource) {
		try (InputStream in = resource.getInputStream()) {
			Properties properties = new Properties();
			properties.load(new StringReader(naturalKeys(decode(in.readAllBytes()))));
			return properties;
		} catch (CharacterCodingException e) {
			log.warn("翻译文件不是 UTF-8 编码，已跳过：{}。请把文件另存为 UTF-8", resource.getDescription());
			return null;
		} catch (IOException | IllegalArgumentException e) {
			log.warn("翻译文件读不了，已跳过：{}，原因：{}", resource.getDescription(), e.getMessage());
			return null;
		}
	}

	private static String decode(byte[] bytes) throws CharacterCodingException {
		String text = StandardCharsets.UTF_8.newDecoder()
			.onMalformedInput(CodingErrorAction.REPORT)
			.onUnmappableCharacter(CodingErrorAction.REPORT)
			.decode(ByteBuffer.wrap(bytes))
			.toString();
		// 有些编辑器会在文件开头加一个看不见的标记，不去掉的话第一个键会对不上
		return !text.isEmpty() && text.charAt(0) == '\uFEFF' ? text.substring(1) : text;
	}

	/**
	 * 让键可以直接写空格和冒号。按标准的 properties 规则，键遇到第一个空格或冒号就结束了，
	 * {@code User not found=用户不存在} 的键只是 {@code User}；拿原文当键时这样写又最自然。
	 * 所以在交给标准解析之前，把每行第一个等号左边的空格和冒号补上反斜杠。一行里没有等号的不动，仍按标准规则。
	 */
	static String naturalKeys(String text) {
		int length = text.length();
		StringBuilder out = new StringBuilder(length + 16);
		boolean continued = false;
		int start = 0;
		while (start < length) {
			int end = start;
			while (end < length && text.charAt(end) != '\n' && text.charAt(end) != '\r') {
				end++;
			}
			String line = text.substring(start, end);
			if (continued) {
				// 上一行用反斜杠续过来的，是译文的一部分
				out.append(line);
				continued = endsWithOddBackslashes(line);
			} else if (isCommentOrBlank(line)) {
				out.append(line);
			} else {
				out.append(escapeKey(line));
				continued = endsWithOddBackslashes(line);
			}
			if (end < length) {
				int next = end + 1;
				if (text.charAt(end) == '\r' && next < length && text.charAt(next) == '\n') {
					next++;
				}
				out.append(text, end, next);
				end = next;
			}
			start = end;
		}
		return out.toString();
	}

	private static String escapeKey(String line) {
		int length = line.length();
		int begin = 0;
		while (begin < length && isBlank(line.charAt(begin))) {
			begin++;
		}
		int separator = -1;
		for (int i = begin; i < length; i++) {
			char c = line.charAt(i);
			if (c == '\\') {
				i++;
			} else if (c == '=') {
				separator = i;
				break;
			}
		}
		if (separator <= begin) {
			return line;
		}
		StringBuilder out = new StringBuilder(length + 8);
		out.append(line, 0, begin);
		StringBuilder blanks = new StringBuilder();
		for (int i = begin; i < separator; i++) {
			char c = line.charAt(i);
			if (isBlank(c)) {
				blanks.append(c);
				continue;
			}
			// 键中间的空格补上反斜杠；等号前面紧挨着的空格不算键的一部分，循环结束后原样放回去
			for (int b = 0; b < blanks.length(); b++) {
				out.append('\\').append(blanks.charAt(b));
			}
			blanks.setLength(0);
			if (c == '\\' && i + 1 < separator) {
				out.append(c).append(line.charAt(++i));
			} else {
				if (c == ':') {
					out.append('\\');
				}
				out.append(c);
			}
		}
		return out.append(blanks).append(line, separator, length).toString();
	}

	private static boolean isCommentOrBlank(String line) {
		int i = 0;
		while (i < line.length() && isBlank(line.charAt(i))) {
			i++;
		}
		return i >= line.length() || line.charAt(i) == '#' || line.charAt(i) == '!';
	}

	private static boolean endsWithOddBackslashes(String line) {
		int count = 0;
		for (int i = line.length() - 1; i >= 0 && line.charAt(i) == '\\'; i--) {
			count++;
		}
		return count % 2 == 1;
	}

	private static boolean isBlank(char c) {
		return c == ' ' || c == '\t' || c == '\f';
	}

	/**
	 * 文件名拆出来的结果。
	 *
	 * @param bundle 名字
	 * @param locale 语言；不分语言的兜底文件是 {@link Locale#ROOT}
	 * @param problem 文件名写错了时说明错在哪里（这时文件不读）；没问题是 null
	 */
	record ParsedName(String bundle, Locale locale, String problem) {
	}

	/** 扫到的一个文件：它的地址（用来去重）、文件本身、文件名拆出来的结果、文件名里是不是写了名字 */
	private record Found(String identity, Resource resource, ParsedName name, boolean named) {
	}

	/** 一个待读的文件。group：0 是应用自己的，1 是其它；rank：应用自己的文件之间的先后；index：在类路径上被找到的先后 */
	private record Source(int group, int rank, String bundle, Locale locale, Resource resource, int index) {
	}
}
