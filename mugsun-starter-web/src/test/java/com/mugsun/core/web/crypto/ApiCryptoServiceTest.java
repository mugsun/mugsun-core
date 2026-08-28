package com.mugsun.core.web.crypto;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 接口国密 SM4（CBC + 随机 IV）：密文格式为 Hex(IV) ‖ Hex(密文)，须与前端 sm-crypto 互通。
 * 重点守住三件事：随机 IV 使密文非确定性、往返可解、非法密文被明确拒绝。
 */
class ApiCryptoServiceTest {

	private static final int IV_HEX_LEN = 32;

	private ApiCryptoService service;

	private ApiCryptoService newService(String key, boolean strictKeys) {
		ApiCryptoService s = new ApiCryptoService();
		ReflectionTestUtils.setField(s, "apiKey", key);
		ReflectionTestUtils.setField(s, "strictKeys", strictKeys);
		return s;
	}

	@BeforeEach
	void setUp() {
		service = newService("unit-test-key-16", false);
		service.init();
	}

	@Test
	@DisplayName("加密后可解回原文（含中文与符号）")
	void roundTrip() {
		String plain = "{\"userName\":\"张三\",\"pwd\":\"P@ss w0rd/+=\"}";

		assertThat(service.decrypt(service.encrypt(plain))).isEqualTo(plain);
	}

	@Test
	@DisplayName("空串与长文本均可往返")
	void roundTripEdgeLengths() {
		assertThat(service.decrypt(service.encrypt(""))).isEmpty();

		String longText = "数据".repeat(5000);
		assertThat(service.decrypt(service.encrypt(longText))).isEqualTo(longText);
	}

	@Test
	@DisplayName("同一明文两次加密结果不同（随机 IV，消除 ECB 确定性）")
	void randomIvMakesCipherNonDeterministic() {
		String plain = "same-plain-text";

		String first = service.encrypt(plain);
		String second = service.encrypt(plain);

		assertThat(first).isNotEqualTo(second);
		assertThat(first.substring(0, IV_HEX_LEN)).isNotEqualTo(second.substring(0, IV_HEX_LEN));
		assertThat(service.decrypt(first)).isEqualTo(plain);
		assertThat(service.decrypt(second)).isEqualTo(plain);
	}

	@Test
	@DisplayName("密文前 32 位十六进制为 16 字节 IV")
	void cipherCarriesHexIvPrefix() {
		String cipher = service.encrypt("payload");

		assertThat(cipher.length()).isGreaterThan(IV_HEX_LEN);
		assertThat(cipher.substring(0, IV_HEX_LEN)).matches("[0-9a-f]{32}");
	}

	@Test
	@DisplayName("密文缺少 IV 段时明确报错，而非抛出难以定位的解密异常")
	void rejectsCipherWithoutIv() {
		assertThatThrownBy(() -> service.decrypt(null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("IV");
		assertThatThrownBy(() -> service.decrypt(""))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> service.decrypt("a".repeat(IV_HEX_LEN)))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("换密钥后无法解开旧密文（密钥确实参与运算）")
	void otherKeyCannotDecrypt() {
		String cipher = service.encrypt("secret");

		ApiCryptoService other = newService("another-key-1616", false);
		other.init();

		assertThatThrownBy(() -> other.decrypt(cipher)).isInstanceOf(Exception.class);
	}

	@Test
	@DisplayName("密钥不足 16 字节按零字节补齐，仍可往返")
	void shortKeyIsPaddedToBlockSize() {
		ApiCryptoService shortKey = newService("short", false);
		shortKey.init();

		assertThat(shortKey.decrypt(shortKey.encrypt("hello"))).isEqualTo("hello");
	}

	@Test
	@DisplayName("未配置密钥且非严格模式：回落开发默认值，可用但仅限本地")
	void fallsBackToDevKeyWhenNotStrict() {
		ApiCryptoService noKey = newService("", false);
		noKey.init();

		assertThat(noKey.decrypt(noKey.encrypt("dev"))).isEqualTo("dev");
	}

	@Test
	@DisplayName("严格模式下缺密钥直接拒绝启动")
	void strictModeRejectsMissingKey() {
		ApiCryptoService strict = newService("   ", true);

		assertThatThrownBy(strict::init)
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("MUGSUN_API_KEY");
	}

	@Test
	@DisplayName("密钥两端空白被裁剪：带空格与不带空格的密钥互通")
	void keyIsTrimmed() {
		ApiCryptoService padded = newService("  unit-test-key-16  ", false);
		padded.init();

		assertThat(padded.decrypt(service.encrypt("cross"))).isEqualTo("cross");
	}
}
