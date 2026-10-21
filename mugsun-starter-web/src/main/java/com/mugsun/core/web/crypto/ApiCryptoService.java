package com.mugsun.core.web.crypto;

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
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * 接口传输国密加解密服务：<b>SM4-GCM</b>（BouncyCastle），带完整性校验。
 * <p>密文格式 = {@code base64(IV_12字节 ‖ 密文 ‖ GCM 标签_16字节)}，标准字母表、必须带填充。
 * 跨端测试数据见测试资源 {@code crypto/api-crypto-vectors.json}（与 mugsun-pc 共用）。
 * <p><b>只是传输混淆，不能代替 TLS</b>：对称密钥要下发到浏览器，对客户端不保密。
 * <p>不防重放、不绑定接口：密文没有 AAD，可被原样重发或搬到另一个加密接口。
 * <p>密钥经 {@code mugsun.crypto.api-key}（环境变量 {@code MUGSUN_API_KEY}）注入，
 * 必须是 32 位 hex 或解码后正好 16 字节的 base64，否则拒绝启动。
 * 未配置时默认拒绝启动；仅当 {@code allow-ephemeral-keys=true} 时生成只在本进程内有效的随机密钥
 * （{@code strict-keys=true} 时仍拒绝）。
 */
public class ApiCryptoService {

	private static final Logger log = LoggerFactory.getLogger(ApiCryptoService.class);

	static final int KEY_BYTES = 16;
	static final int IV_BYTES = 12;
	static final int TAG_BITS = 128;
	private static final Pattern HEX_KEY = Pattern.compile("^[0-9a-fA-F]{32}$");
	private static final Pattern B64 = Pattern.compile("^[A-Za-z0-9+/]+={0,2}$");
	/** 解密失败一律同一个错误，不区分 IV、标签、格式，避免 oracle */
	static final String DECRYPT_FAILED = "接口密文无效";
	private static final Provider BC = new BouncyCastleProvider();

	@Value("${mugsun.crypto.api-key:}")
	private String apiKey;

	/**
	 * 已有的生产开关（boot 的 prod 配置设为 true，同时管 SM2/SM4 等密钥）。
	 * 对接口加密只起一票否决：为 true 时即使 allow-ephemeral-keys=true 也拒绝启动。
	 * 接口加密侧只作否决：防止 dev 配置或 allow-ephemeral-keys 误进生产。
	 */
	@Value("${mugsun.crypto.strict-keys:false}")
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
				throw new IllegalStateException("未配置接口加密密钥 MUGSUN_API_KEY（32 位 hex 或带填充的 16 字节 base64），"
					+ "拒绝启动；仅本地开发可设 mugsun.crypto.allow-ephemeral-keys=true");
			}
			byte[] k = new byte[KEY_BYTES];
			secureRandom.nextBytes(k);
			key = new SecretKeySpec(k, "SM4");
			log.warn("未配置 MUGSUN_API_KEY，已生成只在本进程内有效的临时接口密钥："
				+ "重启后密钥会变，多实例之间不互通，仅限本地开发/测试");
			return;
		}
		key = new SecretKeySpec(parseKey(apiKey), "SM4");
	}

	/**
	 * 解析密钥（与 mugsun-pc 共用测试向量 crypto/api-key-vectors.json）：
	 * 先去掉前后空白；只接受 32 位 hex（大小写均可），或标准字母表、带填充、
	 * 规范编码（解码后重新编码与输入一致）的 base64，且解码后正好 16 字节。其他一律拒绝。
	 */
	static byte[] parseKey(String raw) {
		String s = raw == null ? "" : raw.strip();
		if (s.isEmpty()) {
			throw new IllegalStateException("未配置接口加密密钥 MUGSUN_API_KEY，拒绝启动");
		}
		if (HEX_KEY.matcher(s).matches()) {
			return HexFormat.of().parseHex(s);
		}
		byte[] b = decodeCanonicalBase64(s);
		if (b != null && b.length == KEY_BYTES) {
			return b;
		}
		throw new IllegalStateException("MUGSUN_API_KEY 格式不对：须为 32 位 hex，或带填充的标准 base64 且解码后正好 16 字节，拒绝启动");
	}

	/** 明文 → base64(IV ‖ 密文 ‖ 标签)；每次 CSPRNG 随机 12 字节 IV */
	public String encrypt(String plain) {
		byte[] iv = new byte[IV_BYTES];
		secureRandom.nextBytes(iv);
		return encryptWithIv(plain, iv);
	}

	/** 固定 IV 加密，仅供跨端测试向量使用；业务代码不得调用 */
	String encryptWithIv(String plain, byte[] iv) {
		try {
			Cipher c = Cipher.getInstance("SM4/GCM/NoPadding", BC);
			c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
			byte[] body = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
			byte[] out = new byte[IV_BYTES + body.length];
			System.arraycopy(iv, 0, out, 0, IV_BYTES);
			System.arraycopy(body, 0, out, IV_BYTES, body.length);
			return Base64.getEncoder().encodeToString(out);
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException("接口加密失败", e);
		}
	}

	/** base64(IV ‖ 密文 ‖ 标签) → 明文；格式错误、被篡改、密钥不对都抛同一个错误 */
	public String decrypt(String cipher) {
		byte[] raw = decodeCanonicalBase64(cipher);
		if (raw == null || raw.length < IV_BYTES + TAG_BITS / 8) {
			throw new IllegalArgumentException(DECRYPT_FAILED);
		}
		try {
			Cipher c = Cipher.getInstance("SM4/GCM/NoPadding", BC);
			c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, raw, 0, IV_BYTES));
			return new String(c.doFinal(raw, IV_BYTES, raw.length - IV_BYTES), StandardCharsets.UTF_8);
		} catch (GeneralSecurityException | RuntimeException e) {
			throw new IllegalArgumentException(DECRYPT_FAILED);
		}
	}

	/**
	 * 标准字母表、必须带填充的 base64；解码后重新编码须与输入一致（拒绝缺填充、url-safe、空白、非规范编码）。
	 * 不合规返回 null。密钥和密文共用这条规则。
	 */
	static byte[] decodeCanonicalBase64(String s) {
		if (s == null || s.isEmpty() || s.length() % 4 != 0 || !B64.matcher(s).matches()) {
			return null;
		}
		try {
			byte[] b = Base64.getDecoder().decode(s);
			return Base64.getEncoder().encodeToString(b).equals(s) ? b : null;
		} catch (IllegalArgumentException e) {
			return null;
		}
	}
}
