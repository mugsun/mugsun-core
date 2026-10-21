package com.mugsun.core.web.crypto;

import cn.hutool.core.util.HexUtil;
import jakarta.annotation.PostConstruct;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Provider;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.regex.Pattern;

/**
 * 接口传输国密加解密服务：<b>SM4-GCM</b>（BouncyCastle），带完整性校验。
 * <p>密文格式 = {@code Hex(IV_12字节) ‖ Hex(密文 ‖ GCM 标签_16字节)}。
 * <p><b>只是传输混淆，不能代替 TLS</b>：对称密钥要下发到浏览器，对客户端不保密。
 * <p>密钥经 {@code mugsun.crypto.api-key}（环境变量 {@code MUGSUN_API_KEY}）注入，
 * 必须是 32 位 hex 或解码后正好 16 字节的 base64，否则拒绝启动。
 * 未配置时默认拒绝启动；仅当 {@code allow-ephemeral-keys=true} 且未开 {@code strict-keys}
 * 时，生成只在本进程内有效的随机密钥。
 */
public class ApiCryptoService {

	private static final Logger log = LoggerFactory.getLogger(ApiCryptoService.class);

	static final int KEY_BYTES = 16;
	static final int IV_BYTES = 12;
	static final int TAG_BITS = 128;
	private static final int IV_HEX = IV_BYTES * 2;
	private static final int MIN_CIPHER_HEX = IV_HEX + TAG_BITS / 4;
	private static final Pattern HEX_KEY = Pattern.compile("^[0-9a-fA-F]{32}$");
	private static final Pattern HEX_BODY = Pattern.compile("^[0-9a-fA-F]+$");
	/** 解密失败一律同一个错误，不区分 IV、标签、格式，避免 oracle */
	static final String DECRYPT_FAILED = "接口密文无效";
	private static final Provider BC = new BouncyCastleProvider();

	@Value("${mugsun.crypto.api-key:}")
	private String apiKey;

	/** 严格模式（默认开）：缺密钥即拒绝启动，连临时密钥也不允许 */
	@Value("${mugsun.crypto.strict-keys:true}")
	private boolean strictKeys;

	/** 显式允许：缺密钥时生成只在本进程内有效的随机密钥（仅本地开发/测试用） */
	@Value("${mugsun.crypto.allow-ephemeral-keys:false}")
	private boolean allowEphemeralKeys;

	private SecretKeySpec key;
	private final SecureRandom secureRandom = new SecureRandom();

	@PostConstruct
	public void init() {
		if (apiKey == null || apiKey.isBlank()) {
			if (strictKeys || !allowEphemeralKeys) {
				throw new IllegalStateException("未配置接口加密密钥 MUGSUN_API_KEY（32 位 hex 或 16 字节 base64），拒绝启动；"
					+ "本地开发可设 mugsun.crypto.allow-ephemeral-keys=true 且 strict-keys=false");
			}
			byte[] k = new byte[KEY_BYTES];
			secureRandom.nextBytes(k);
			key = new SecretKeySpec(k, "SM4");
			log.warn("未配置 MUGSUN_API_KEY，已生成只在本进程内有效的临时接口密钥："
				+ "重启后密钥会变，多实例之间不互通，仅限本地开发/测试");
			return;
		}
		key = new SecretKeySpec(parseKey(apiKey.trim()), "SM4");
	}

	static byte[] parseKey(String s) {
		if (HEX_KEY.matcher(s).matches()) {
			return HexUtil.decodeHex(s);
		}
		byte[] b;
		try {
			b = Base64.getDecoder().decode(s);
		} catch (IllegalArgumentException e) {
			throw new IllegalStateException("MUGSUN_API_KEY 格式不对：须为 32 位 hex 或 base64（解码后 16 字节），拒绝启动");
		}
		if (b.length != KEY_BYTES) {
			throw new IllegalStateException("MUGSUN_API_KEY 长度不对：解码后须正好 16 字节，实际 " + b.length + "，拒绝启动");
		}
		return b;
	}

	/** 明文 → Hex(IV) ‖ Hex(密文 ‖ 标签)；每次 CSPRNG 随机 12 字节 IV */
	public String encrypt(String plain) {
		byte[] iv = new byte[IV_BYTES];
		secureRandom.nextBytes(iv);
		try {
			Cipher c = Cipher.getInstance("SM4/GCM/NoPadding", BC);
			c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
			return HexUtil.encodeHexStr(iv) + HexUtil.encodeHexStr(c.doFinal(plain.getBytes(StandardCharsets.UTF_8)));
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException("接口加密失败", e);
		}
	}

	/** Hex(IV) ‖ Hex(密文 ‖ 标签) → 明文；格式错误、被篡改、密钥不对都抛同一个错误 */
	public String decrypt(String cipher) {
		if (cipher == null || cipher.length() < MIN_CIPHER_HEX || (cipher.length() & 1) != 0
			|| !HEX_BODY.matcher(cipher).matches()) {
			throw new IllegalArgumentException(DECRYPT_FAILED);
		}
		try {
			byte[] iv = HexUtil.decodeHex(cipher.substring(0, IV_HEX));
			Cipher c = Cipher.getInstance("SM4/GCM/NoPadding", BC);
			c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
			return new String(c.doFinal(HexUtil.decodeHex(cipher.substring(IV_HEX))), StandardCharsets.UTF_8);
		} catch (GeneralSecurityException | RuntimeException e) {
			throw new IllegalArgumentException(DECRYPT_FAILED);
		}
	}
}
