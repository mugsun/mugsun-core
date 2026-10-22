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
import java.nio.file.Path;
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
 * <p><b>只是传输混淆，不能代替 HTTPS</b>：对称密钥要下发到浏览器，对客户端不保密。
 * <p>不防重放、不绑定接口：密文没有 AAD，可被原样重发或搬到另一个加密接口。
 * <p>密钥来源（约定优于配置，没配也能启动）：
 * <ol>
 *   <li>配了 {@code mugsun.crypto.api-key}（环境变量 {@code MUGSUN_API_KEY}）就用配的，格式校验严格：
 *       32 位 hex 或解码后正好 16 字节的规范 base64，不对就拒绝启动；等于旧内置默认值也拒绝；</li>
 *   <li>没配时用本地自动生成的密钥文件 {@code <mugsun.home>/api-transport.key}（默认 {@code ~/.mugsun}）：
 *       首次启动 SecureRandom 生成 16 字节，之后重启复用，见 {@link LocalKeyFiles}。</li>
 * </ol>
 * <p>这把密钥只用于传输混淆，会下发给已登录的前端；不得用于落库加密、派生签名，也不得与签名密钥共用。
 */
public class ApiCryptoService {

	private static final Logger log = LoggerFactory.getLogger(ApiCryptoService.class);

	static final int KEY_BYTES = 16;
	static final int IV_BYTES = 12;
	static final int TAG_BITS = 128;
	/** 本地自动生成的接口密钥文件名（与签名密钥分开存） */
	public static final String LOCAL_KEY_FILE = "api-transport.key";
	private static final Pattern HEX_KEY = Pattern.compile("^[0-9a-fA-F]{32}$");
	private static final Pattern B64 = Pattern.compile("^[A-Za-z0-9+/]+={0,2}$");
	/** 已删除的旧内置默认值的 SHA-256（源码里不再出现原值）；配置值等于它直接拒绝启动 */
	private static final String LEGACY_BUILTIN_SHA256 = "e389b2cbf641066e56923b93184da1c45ffb2acbcc2285015bddd9cbf9297c1c";
	/** 解密失败一律同一个错误，不区分 IV、标签、格式，避免 oracle */
	static final String DECRYPT_FAILED = "接口密文无效";
	private static final Provider BC = new BouncyCastleProvider();

	/** 密钥从哪来 */
	public enum KeySource {
		/** 配置项 / 环境变量 MUGSUN_API_KEY */
		CONFIGURED,
		/** 本地自动生成并持久化的文件 */
		AUTO_GENERATED
	}

	@Value("${mugsun.crypto.api-key:}")
	private String apiKey;

	/** 本地密钥目录；环境变量 MUGSUN_HOME 也能覆盖 */
	@Value("${mugsun.home:}")
	private String home;

	private SecretKeySpec key;
	private KeySource keySource;
	private Path localKeyFile;
	private final SecureRandom secureRandom = new SecureRandom();

	@PostConstruct
	public void init() {
		if (apiKey != null && !apiKey.isBlank()) {
			key = new SecretKeySpec(parseKey(apiKey), "SM4");
			keySource = KeySource.CONFIGURED;
			log.info("接口加密使用配置的 MUGSUN_API_KEY");
			return;
		}
		Path file = LocalKeyFiles.resolveHome(home).resolve(LOCAL_KEY_FILE);
		LocalKeyFiles.Loaded loaded = LocalKeyFiles.loadOrCreate(file, () -> {
			byte[] k = new byte[KEY_BYTES];
			secureRandom.nextBytes(k);
			return HexFormat.of().formatHex(k);
		}, v -> HEX_KEY.matcher(v).matches(), "接口加密密钥");
		key = new SecretKeySpec(HexFormat.of().parseHex(loaded.content()), "SM4");
		keySource = KeySource.AUTO_GENERATED;
		localKeyFile = loaded.file();
		if (loaded.created()) {
			log.info("未配置 MUGSUN_API_KEY，已自动生成接口加密密钥并保存到 {}", loaded.file());
		}
		log.warn("接口加密当前使用自动生成的密钥（文件 {}）。Docker 不给该目录挂卷时每次重启都会换新密钥；"
			+ "多实例各自生成会导致密钥不一致、解密失败：这两种部署请配置 MUGSUN_API_KEY 或挂共享卷", loaded.file());
		if (LocalKeyFiles.inContainer()) {
			log.warn("检测到容器环境且没配 MUGSUN_API_KEY：请配置 MUGSUN_API_KEY，或给 {} 挂持久卷", loaded.file().getParent());
		}
	}

	/** 当前密钥来源 */
	public KeySource keySource() {
		return keySource;
	}

	/** 自动生成时的密钥文件路径；用配置值时为 null */
	public Path localKeyFile() {
		return localKeyFile;
	}

	/**
	 * 传输密钥的 32 位小写 hex，只供「已登录会话的启动下发接口」使用，不得写日志或审计。
	 * 配了 MUGSUN_API_KEY 时就是配置值（统一成 hex，字节相同）。
	 */
	public String transportKeyHex() {
		return HexFormat.of().formatHex(key.getEncoded());
	}

	/**
	 * 解析密钥（与 mugsun-pc 共用测试向量 crypto/api-crypto-vectors.json）：
	 * 先去掉前后空白；只接受 32 位 hex（大小写均可），或标准字母表、带填充、
	 * 规范编码（解码后重新编码与输入一致）的 base64，且解码后正好 16 字节。其他一律拒绝。
	 */
	static byte[] parseKey(String raw) {
		String s = raw == null ? "" : raw.strip();
		if (s.isEmpty()) {
			throw new IllegalStateException("未配置接口加密密钥 MUGSUN_API_KEY，拒绝启动");
		}
		// legacy 写法：16 个字符按 UTF-8 原样当 16 字节（旧版行为），只用于拒绝旧默认值，不作为合法格式
		byte[] legacy = s.getBytes(StandardCharsets.UTF_8);
		if (legacy.length == KEY_BYTES) {
			rejectLegacyBuiltin(legacy);
		}
		if (HEX_KEY.matcher(s).matches()) {
			return rejectLegacyBuiltin(HexFormat.of().parseHex(s));
		}
		byte[] b = decodeCanonicalBase64(s);
		if (b != null && b.length == KEY_BYTES) {
			return rejectLegacyBuiltin(b);
		}
		throw new IllegalStateException("MUGSUN_API_KEY 格式不对：须为 32 位 hex，或带填充的标准 base64 且解码后正好 16 字节，拒绝启动");
	}

	/** 对解码后的 16 字节算 SHA-256，与旧内置默认值比对；hex、base64、legacy 任何写法都拒绝 */
	private static byte[] rejectLegacyBuiltin(byte[] key) {
		if (isLegacyBuiltin(key)) {
			throw new IllegalStateException("MUGSUN_API_KEY 等于已删除的旧内置默认值，拒绝启动："
				+ "请换成新密钥（openssl rand -hex 16），或删掉该配置让系统自动生成");
		}
		return key;
	}

	static boolean isLegacyBuiltin(byte[] key) {
		try {
			byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest(key);
			return HexFormat.of().formatHex(d).equals(LEGACY_BUILTIN_SHA256);
		} catch (java.security.NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	/** 明文 → base64(IV ‖ 密文 ‖ 标签)；每次 CSPRNG 随机 12 字节 IV；null 原样返回 null */
	public String encrypt(String plain) {
		if (plain == null) {
			return null;
		}
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

	/** base64(IV ‖ 密文 ‖ 标签) → 明文；null 原样返回 null（与 encrypt 对称）；格式错误、被篡改、密钥不对都抛同一个错误 */
	public String decrypt(String cipher) {
		if (cipher == null) {
			return null;
		}
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
