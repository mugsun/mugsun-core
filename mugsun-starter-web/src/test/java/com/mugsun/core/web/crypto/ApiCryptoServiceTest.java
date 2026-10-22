package com.mugsun.core.web.crypto;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 接口 SM4-GCM：密文 = Hex(IV_12) ‖ Hex(密文 ‖ 标签_16)。
 * 守住：未配置自动生成并复用、配置优先、密钥格式、篡改即失败且错误统一、源码无写死密钥。
 */
class ApiCryptoServiceTest {

	/** 每次运行随机生成，源码里不放任何密钥字面量 */
	private static final String HEX_KEY = randomHexKey();
	private static final String OTHER_HEX_KEY = randomHexKey();

	private static String randomHexKey() {
		byte[] k = new byte[16];
		new java.security.SecureRandom().nextBytes(k);
		return HexFormat.of().formatHex(k);
	}

	private ApiCryptoService service;

	@TempDir
	static Path sharedHome;

	@TempDir
	Path home;

	/** strict/ephemeral 两个旧参数已无作用，保留签名只为少改用例；home 指向临时目录，绝不碰 ~/.mugsun */
	private static ApiCryptoService newService(String key, boolean strict, boolean ephemeral) {
		return newService(key, sharedHome);
	}

	private static ApiCryptoService newService(String key, Path home) {
		ApiCryptoService s = new ApiCryptoService();
		ReflectionTestUtils.setField(s, "apiKey", key);
		ReflectionTestUtils.setField(s, "home", home.toString());
		return s;
	}

	@BeforeEach
	void setUp() {
		service = newService(HEX_KEY, true, false);
		service.init();
	}

	@Test
	@DisplayName("往返：中文、符号、空串、长文本")
	void roundTrip() {
		String plain = "{\"userName\":\"张三\",\"pwd\":\"P@ss w0rd/+=\"}";
		assertThat(service.decrypt(service.encrypt(plain))).isEqualTo(plain);
		assertThat(service.decrypt(service.encrypt(""))).isEmpty();
		String longText = "数据".repeat(5000);
		assertThat(service.decrypt(service.encrypt(longText))).isEqualTo(longText);
	}

	@Test
	@DisplayName("同一明文两次加密结果不同；输出是带填充的标准 base64，前 12 字节是 IV")
	void randomIv() {
		String a = service.encrypt("same");
		String b = service.encrypt("same");
		assertThat(a).isNotEqualTo(b);
		byte[] ra = Base64.getDecoder().decode(a);
		byte[] rb = Base64.getDecoder().decode(b);
		assertThat(Base64.getEncoder().encodeToString(ra)).isEqualTo(a);
		assertThat(ra).hasSize(12 + "same".length() + 16);
		assertThat(java.util.Arrays.copyOf(ra, 12)).isNotEqualTo(java.util.Arrays.copyOf(rb, 12));
	}

	@Test
	@DisplayName("base64 密钥（16 字节）与等值 hex 密钥互通")
	void base64KeyEquivalentToHex() {
		String b64 = Base64.getEncoder().encodeToString(HexFormat.of().parseHex(HEX_KEY));
		ApiCryptoService s = newService(" " + b64 + " ", true, false);
		s.init();
		assertThat(s.decrypt(service.encrypt("cross"))).isEqualTo("cross");
	}

	@Test
	@DisplayName("未配置：首次启动生成 16 字节密钥，文件 600、目录 700，来源为自动生成")
	void missingKeyGeneratesLocalFile() throws IOException {
		Path dir = home.resolve("nested/.mugsun");
		ApiCryptoService s = newService("", dir);
		s.init();
		Path f = dir.resolve(ApiCryptoService.LOCAL_KEY_FILE);
		assertThat(f).exists();
		assertThat(s.keySource()).isEqualTo(ApiCryptoService.KeySource.AUTO_GENERATED);
		assertThat(s.localKeyFile()).isEqualTo(f);
		String content = Files.readString(f).strip();
		assertThat(content).matches("[0-9a-f]{32}").isEqualTo(s.transportKeyHex());
		if (Files.getFileStore(f).supportsFileAttributeView("posix")) {
			assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(f))).isEqualTo("rw-------");
			assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(dir))).isEqualTo("rwx------");
		}
		assertThat(s.decrypt(s.encrypt("auto"))).isEqualTo("auto");
		// 目录里不留临时文件
		try (Stream<Path> files = Files.list(dir)) {
			assertThat(files.map(p -> p.getFileName().toString())).noneMatch(n -> n.endsWith(".tmp"));
		}
	}

	@Test
	@DisplayName("重启复用同一把密钥：第二次启动能解开第一次的密文")
	void restartReusesKey() {
		ApiCryptoService first = newService(null, home);
		first.init();
		String cipher = first.encrypt("reuse");
		ApiCryptoService second = newService("  ", home);
		second.init();
		assertThat(second.transportKeyHex()).isEqualTo(first.transportKeyHex());
		assertThat(second.decrypt(cipher)).isEqualTo("reuse");
	}

	@Test
	@DisplayName("配置值优先：有本地文件也用配置值，且不新建文件")
	void configuredKeyWins() {
		ApiCryptoService auto = newService("", home);
		auto.init();
		ApiCryptoService s = newService(HEX_KEY, home);
		s.init();
		assertThat(s.keySource()).isEqualTo(ApiCryptoService.KeySource.CONFIGURED);
		assertThat(s.localKeyFile()).isNull();
		assertThat(s.transportKeyHex()).isEqualTo(HEX_KEY.toLowerCase()).isNotEqualTo(auto.transportKeyHex());
		Path other = home.resolve("unused");
		newService(HEX_KEY, other).init();
		assertThat(other).doesNotExist();
	}

	@Test
	@DisplayName("旧内置默认值 legacy 写法（16 个字符原样）：拒绝启动")
	void legacyBuiltinRejected() {
		String legacy = "mugsun-api-" + "key16";
		assertThatThrownBy(newService(legacy, home)::init)
			.isInstanceOf(IllegalStateException.class).hasMessageContaining("旧内置默认值");
		assertThatThrownBy(newService(" " + legacy + " ", home)::init)
			.isInstanceOf(IllegalStateException.class).hasMessageContaining("旧内置默认值");	}

	@Test
	@DisplayName("旧内置默认值写成 hex：按解码后字节比对，拒绝启动")
	void legacyBuiltinAsHexRejected() {
		String hex = HexFormat.of().formatHex(("mugsun-api-" + "key16").getBytes(java.nio.charset.StandardCharsets.UTF_8));
		for (String v : List.of(hex, hex.toUpperCase())) {
			assertThatThrownBy(newService(v, home)::init)
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("旧内置默认值");
		}
	}

	@Test
	@DisplayName("旧内置默认值写成 base64：按解码后字节比对，拒绝启动")
	void legacyBuiltinAsBase64Rejected() {
		String b64 = Base64.getEncoder().encodeToString(("mugsun-api-" + "key16").getBytes(java.nio.charset.StandardCharsets.UTF_8));
		assertThatThrownBy(newService(b64, home)::init)
			.isInstanceOf(IllegalStateException.class).hasMessageContaining("旧内置默认值");
	}

	@Test
	@DisplayName("本地文件被改坏：拒绝启动，不悄悄换钥")
	void corruptLocalFileRejected() throws IOException {
		Files.writeString(home.resolve(ApiCryptoService.LOCAL_KEY_FILE), "not-a-key\n");
		assertThatThrownBy(newService("", home)::init).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining(ApiCryptoService.LOCAL_KEY_FILE);
	}

	@Test
	@DisplayName("日志不含密钥：自动生成和配置两种情况，所有日志都不出现密钥 hex/base64")
	void logsNeverContainKey() {
		Logger logger = (Logger) LoggerFactory.getLogger(ApiCryptoService.class);
		Logger filesLogger = (Logger) LoggerFactory.getLogger(LocalKeyFiles.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		filesLogger.addAppender(appender);
		try {
			ApiCryptoService auto = newService("", home);
			auto.init();
			ApiCryptoService conf = newService(HEX_KEY, home);
			conf.init();
			assertThat(appender.list).isNotEmpty();
			assertThat(appender.list).anyMatch(e -> e.getFormattedMessage().contains(home.toString()));
			for (String secret : List.of(auto.transportKeyHex(), HEX_KEY,
				Base64.getEncoder().encodeToString(HexFormat.of().parseHex(auto.transportKeyHex())))) {
				assertThat(appender.list).noneMatch(e -> e.getFormattedMessage().toLowerCase().contains(secret.toLowerCase()));
			}
		} finally {
			logger.detachAppender(appender);
			filesLogger.detachAppender(appender);
		}
	}

	@Test
	@DisplayName("encrypt(null) 返回 null，不抛空指针")
	void encryptNullReturnsNull() {
		assertThat(service.encrypt(null)).isNull();
	}

	@Test
	@DisplayName("decrypt(null) 返回 null，与 encrypt(null) 对称；空串仍按非法密文报错")
	void decryptNullReturnsNull() {
		assertThat(service.decrypt(null)).isNull();
		assertThatThrownBy(() -> service.decrypt("")).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("密钥格式或长度不对：拒绝启动，不补零也不截断")
	void malformedKeysRejected() {
		for (String bad : List.of("short", "unit-test-key-16", HEX_KEY + "00", HEX_KEY.substring(2),
			Base64.getEncoder().encodeToString(new byte[15]), Base64.getEncoder().encodeToString(new byte[32]),
			"zz" + HEX_KEY.substring(2))) {
			assertThatThrownBy(newService(bad, true, false)::init)
				.as("key=%s", bad).isInstanceOf(IllegalStateException.class);
		}
	}

	@Test
	@DisplayName("跨端测试数据（与 mugsun-pc 共用）：密钥解析、固定 IV 加密向量、非法密文")
	void sharedVectors() throws IOException {
		com.fasterxml.jackson.databind.JsonNode root;
		try (var in = getClass().getResourceAsStream("/crypto/api-crypto-vectors.json")) {
			root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(in);
		}
		assertThat(root.path("validKeys").size()).isGreaterThan(0);
		for (var v : root.path("validKeys")) {
			assertThat(HexFormat.of().formatHex(ApiCryptoService.parseKey(v.path("input").asText())))
				.as(v.path("name").asText()).isEqualTo(v.path("bytesHex").asText());
			newService(v.path("input").asText(), true, false).init();
		}
		assertThat(root.path("invalidKeys").size()).isGreaterThan(0);
		for (var v : root.path("invalidKeys")) {
			// 空白输入在启动时走自动生成；向量只验解析规则本身
			assertThatThrownBy(() -> ApiCryptoService.parseKey(v.path("input").asText()))
				.as(v.path("name").asText()).isInstanceOf(IllegalStateException.class);
		}
		assertThat(root.path("encryptVectors").size()).isGreaterThan(0);
		for (var v : root.path("encryptVectors")) {
			ApiCryptoService s = newService(v.path("sm4Hex").asText(), true, false);
			s.init();
			String plain = v.path("plaintext").asText();
			assertThat(s.encryptWithIv(plain, HexFormat.of().parseHex(v.path("ivHex").asText())))
				.as(plain).isEqualTo(v.path("ciphertext").asText());
			assertThat(s.decrypt(v.path("ciphertext").asText())).isEqualTo(plain);
		}
		var inv = root.path("invalidCiphertexts");
		ApiCryptoService s = newService(inv.path("sm4Hex").asText(), true, false);
		s.init();
		assertThat(inv.path("cases").size()).isGreaterThan(0);
		for (var v : inv.path("cases")) {
			assertThatThrownBy(() -> s.decrypt(v.path("ciphertext").asText())).as(v.path("name").asText())
				.isInstanceOf(IllegalArgumentException.class).hasMessage(ApiCryptoService.DECRYPT_FAILED);
		}
	}

	@Test
	@DisplayName("密文被篡改（IV、正文、标签任一位）、格式不对或换密钥：解密失败，错误信息统一")
	void tamperingFailsWithUniformError() {
		String cipher = service.encrypt("{\"amount\":100}");
		byte[] raw = Base64.getDecoder().decode(cipher);
		ApiCryptoService other = newService(OTHER_HEX_KEY, true, false);
		other.init();
		List<String> bad = List.of(flip(raw, 0), flip(raw, 13), flip(raw, raw.length - 1),
			Base64.getEncoder().encodeToString(java.util.Arrays.copyOf(raw, raw.length - 1)),
			HexFormat.of().formatHex(raw), "", cipher + "=", " " + cipher);
		for (String c : bad) {
			assertThatThrownBy(() -> service.decrypt(c)).as(c).isInstanceOf(IllegalArgumentException.class)
				.hasMessage(ApiCryptoService.DECRYPT_FAILED);
		}
		assertThatThrownBy(() -> other.decrypt(cipher)).hasMessage(ApiCryptoService.DECRYPT_FAILED);
	}

	@Test
	@DisplayName("源码里没有写死的接口密钥或开发回落值")
	void noHardcodedKeyInSource() throws IOException {
		Path src = Path.of("src/main/java/com/mugsun/core/web/crypto/ApiCryptoService.java");
		String code = Files.readString(src);
		// 不再出现任何 key = "..." 的字符串字面量赋值，也不再有 16 字节可打印回落值
		assertThat(code).doesNotContainPattern("key\\s*=\\s*\"[^\"]+\"");
		assertThat(code).doesNotContainPattern("\"[!-~]{16}\"");
		assertThat(code).doesNotContainPattern("\"[0-9a-fA-F]{32}\"");
		try (Stream<Path> files = Files.walk(Path.of("src/main"))) {
			files.filter(Files::isRegularFile).forEach(f -> {
				try {
					assertThat(Files.readString(f)).as(f.toString())
						.doesNotContainPattern("api-key\\s*[:=]\\s*[^$\\s{}\"]");
				} catch (IOException e) {
					throw new IllegalStateException(e);
				}
			});
		}
	}

	private static String flip(byte[] raw, int idx) {
		byte[] c = raw.clone();
		c[idx] ^= 1;
		return Base64.getEncoder().encodeToString(c);
	}
}
