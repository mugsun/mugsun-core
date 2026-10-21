package com.mugsun.core.web.crypto;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 接口 SM4-GCM：密文 = Hex(IV_12) ‖ Hex(密文 ‖ 标签_16)。
 * 守住：默认拒绝启动、显式临时密钥、密钥格式、篡改即失败且错误统一、源码无写死密钥。
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

	private static ApiCryptoService newService(String key, boolean strict, boolean ephemeral) {
		ApiCryptoService s = new ApiCryptoService();
		ReflectionTestUtils.setField(s, "apiKey", key);
		ReflectionTestUtils.setField(s, "strictKeys", strict);
		ReflectionTestUtils.setField(s, "allowEphemeralKeys", ephemeral);
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
	@DisplayName("缺密钥：默认拒绝启动")
	void missingKeyRejectedByDefault() {
		assertThatThrownBy(newService("", true, false)::init)
			.isInstanceOf(IllegalStateException.class).hasMessageContaining("MUGSUN_API_KEY");
		assertThatThrownBy(newService(null, false, false)::init)
			.isInstanceOf(IllegalStateException.class).hasMessageContaining("MUGSUN_API_KEY");
	}

	@Test
	@DisplayName("strict-keys 开启时，即使 allow-ephemeral-keys=true 也拒绝启动")
	void strictBeatsEphemeral() {
		assertThatThrownBy(newService("  ", true, true)::init).isInstanceOf(IllegalStateException.class);
	}

	@Test
	@DisplayName("allow-ephemeral-keys=true：能启动，两次启动密钥不同")
	void ephemeralKeyDiffersPerStart() {
		ApiCryptoService first = newService("", false, true);
		first.init();
		ApiCryptoService second = newService("", false, true);
		second.init();
		String cipher = first.encrypt("eph");
		assertThat(first.decrypt(cipher)).isEqualTo("eph");
		assertThatThrownBy(() -> second.decrypt(cipher)).isInstanceOf(IllegalArgumentException.class);
		Object k1 = ReflectionTestUtils.getField(first, "key");
		Object k2 = ReflectionTestUtils.getField(second, "key");
		assertThat(k1).isNotEqualTo(k2);
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
			assertThatThrownBy(newService(v.path("input").asText(), false, false)::init)
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
		assertThatThrownBy(() -> service.decrypt(null)).hasMessage(ApiCryptoService.DECRYPT_FAILED);
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
