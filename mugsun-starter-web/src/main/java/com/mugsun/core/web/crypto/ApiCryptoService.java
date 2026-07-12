package com.mugsun.core.web.crypto;

import cn.hutool.core.util.HexUtil;
import cn.hutool.crypto.Mode;
import cn.hutool.crypto.Padding;
import cn.hutool.crypto.symmetric.SM4;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * 接口传输国密 SM4 加解密服务：<b>CBC + 随机 IV</b>（消除 ECB 确定性弱点；Hutool SM4 不支持 GCM，CBC 满足「加 IV」）。
 * <p>密文格式 = {@code Hex(IV_16字节) ‖ Hex(密文)}，与前端 sm-crypto {@code sm4.encrypt/decrypt(mode:'cbc', iv)} 跨库互通
 * （同 SM4-128 / PKCS7(=PKCS5) / 16 字节 IV）。密钥经环境变量 {@code MUGSUN_API_KEY} 外部注入。
 */
public class ApiCryptoService {

	private static final int BLOCK = 16;
	private static final int IV_HEX = BLOCK * 2;

	@Value("${mugsun.crypto.api-key:mugsun-api-key16}")
	private String apiKey;

	private byte[] keyBytes;
	private final SecureRandom secureRandom = new SecureRandom();

	@PostConstruct
	public void init() {
		keyBytes = Arrays.copyOf(apiKey.getBytes(StandardCharsets.UTF_8), BLOCK);
	}

	/** 明文 → Hex(IV) ‖ Hex(密文)；每次 CSPRNG 随机 IV，密文非确定性 */
	public String encrypt(String plain) {
		byte[] iv = new byte[BLOCK];
		secureRandom.nextBytes(iv);
		SM4 sm4 = new SM4(Mode.CBC, Padding.PKCS5Padding, keyBytes, iv);
		return HexUtil.encodeHexStr(iv) + sm4.encryptHex(plain, StandardCharsets.UTF_8);
	}

	/** Hex(IV) ‖ Hex(密文) → 明文：拆前 16 字节 IV 后 CBC 解密 */
	public String decrypt(String cipher) {
		if (cipher == null || cipher.length() <= IV_HEX) {
			throw new IllegalArgumentException("接口密文格式非法（缺少 IV）");
		}
		byte[] iv = HexUtil.decodeHex(cipher.substring(0, IV_HEX));
		String body = cipher.substring(IV_HEX);
		SM4 sm4 = new SM4(Mode.CBC, Padding.PKCS5Padding, keyBytes, iv);
		return new String(sm4.decrypt(HexUtil.decodeHex(body)), StandardCharsets.UTF_8);
	}
}
