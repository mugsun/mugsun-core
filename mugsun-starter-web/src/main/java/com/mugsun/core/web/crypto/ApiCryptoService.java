package com.mugsun.core.web.crypto;

import cn.hutool.core.util.HexUtil;
import cn.hutool.crypto.SmUtil;
import cn.hutool.crypto.symmetric.SM4;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * 接口传输国密 SM4 加解密服务（与前端 sm-crypto 共享密钥，统一 Hex + ECB + PKCS5 保证跨库兼容）。
 */
public class ApiCryptoService {

	@Value("${mugsun.crypto.api-key:mugsun-api-key16}")
	private String apiKey;

	private SM4 sm4;

	@PostConstruct
	public void init() {
		sm4 = SmUtil.sm4(Arrays.copyOf(apiKey.getBytes(StandardCharsets.UTF_8), 16));
	}

	/** 明文 → Hex 密文（与 sm-crypto sm4 默认 hex 输出一致） */
	public String encrypt(String plain) {
		return sm4.encryptHex(plain, StandardCharsets.UTF_8);
	}

	/** Hex 密文 → 明文 */
	public String decrypt(String cipher) {
		return new String(sm4.decrypt(HexUtil.decodeHex(cipher)), StandardCharsets.UTF_8);
	}
}
