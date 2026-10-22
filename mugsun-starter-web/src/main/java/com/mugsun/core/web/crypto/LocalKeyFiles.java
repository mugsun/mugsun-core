package com.mugsun.core.web.crypto;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * 本地自动生成的密钥文件：没配环境变量时首次启动生成、持久化，之后每次启动复用。
 * <ul>
 *   <li>目录默认 {@code ~/.mugsun}，可用 {@code mugsun.home}（环境变量 {@code MUGSUN_HOME}）覆盖；</li>
 *   <li>新建目录 700、文件 600；先写同目录临时文件、fsync，再原子 rename 成正式文件；</li>
 *   <li>并发首启：同 JVM 内按路径加锁，跨进程用同目录 {@code .lock} 文件的排他锁，拿到锁后再检查一次；</li>
 *   <li>读取时权限比 600 宽只打 WARN，不拒绝启动；文件系统不支持 POSIX 权限时打 WARN；</li>
 *   <li>日志只写路径，从不写文件内容。</li>
 * </ul>
 */
public final class LocalKeyFiles {

	private static final Logger log = LoggerFactory.getLogger(LocalKeyFiles.class);

	private static final String FILE_PERMS = "rw-------";
	private static final String DIR_PERMS = "rwx------";
	private static final Map<Path, Object> JVM_LOCKS = new ConcurrentHashMap<>();

	private LocalKeyFiles() {
	}

	/** 读取或新建的结果；{@code content} 是去掉首尾空白的文件内容 */
	public record Loaded(String content, Path file, boolean created) {
		@Override
		public String toString() {
			// 防止误把内容打进日志
			return "Loaded[file=" + file + ", created=" + created + "]";
		}
	}

	/** 解析密钥目录：配置值（mugsun.home）优先，空则 {@code ${user.home}/.mugsun} */
	public static Path resolveHome(String configured) {
		String h = configured == null || configured.isBlank()
			? System.getProperty("user.home") + "/.mugsun"
			: configured.strip();
		return Path.of(h).toAbsolutePath().normalize();
	}

	/**
	 * 读取密钥文件；不存在就用 {@code generator} 生成并原子写入。
	 * 已有文件内容不满足 {@code validator} 时拒绝启动（文件损坏不能悄悄换钥）。
	 */
	public static Loaded loadOrCreate(Path file, Supplier<String> generator, Predicate<String> validator, String label) {
		Path f = file.toAbsolutePath().normalize();
		Path dir = f.getParent();
		try {
			createPrivateDirectories(dir);
			if (!Files.exists(f)) {
				synchronized (JVM_LOCKS.computeIfAbsent(f, k -> new Object())) {
					Path lockFile = dir.resolve(f.getFileName() + ".lock");
					try (FileChannel ch = openLockFile(lockFile); FileLock ignored = ch.lock()) {
						if (!Files.exists(f)) {
							String content = generator.get();
							if (!validator.test(content)) {
								throw new IllegalStateException(label + "生成结果格式不对");
							}
							writeAtomically(f, content);
							return new Loaded(content, f, true);
						}
					}
				}
			}
			warnIfTooOpen(f, label);
			String s = Files.readString(f, StandardCharsets.US_ASCII).strip();
			if (!validator.test(s)) {
				throw new IllegalStateException(label + "文件内容格式不对，拒绝启动；确认没有用它加密过需要保留的数据后，"
					+ "删除该文件重启即可重新生成，或改用环境变量配置：" + f);
			}
			return new Loaded(s, f, false);
		} catch (IOException e) {
			throw new UncheckedIOException(label + "读写失败：" + f, e);
		}
	}

	/** 是否像在容器里：有 /.dockerenv，或 /proc/1/cgroup 提到 docker/kubepods/containerd/lxc */
	public static boolean inContainer() {
		return inContainer(Path.of("/.dockerenv"), Path.of("/proc/1/cgroup"));
	}

	static boolean inContainer(Path dockerEnv, Path cgroup) {
		if (Files.exists(dockerEnv)) {
			return true;
		}
		try {
			if (Files.isReadable(cgroup)) {
				String s = Files.readString(cgroup).toLowerCase(Locale.ROOT);
				return s.contains("docker") || s.contains("kubepods") || s.contains("containerd") || s.contains("lxc");
			}
		} catch (IOException | RuntimeException e) {
			return false;
		}
		return false;
	}

	private static FileChannel openLockFile(Path lockFile) throws IOException {
		Set<StandardOpenOption> opts = EnumSet.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE);
		return posix(lockFile.getParent())
			? FileChannel.open(lockFile, opts, filePerms())
			: FileChannel.open(lockFile, opts);
	}

	private static void writeAtomically(Path f, String content) throws IOException {
		Path dir = f.getParent();
		boolean posix = posix(dir);
		Path tmp = posix
			? Files.createTempFile(dir, f.getFileName() + ".", ".tmp", filePerms())
			: Files.createTempFile(dir, f.getFileName() + ".", ".tmp");
		if (!posix) {
			log.warn("文件系统不支持 POSIX 权限，没法把密钥文件设成 600，请自行限制访问：{}", f);
		}
		try {
			try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
				ch.write(StandardCharsets.US_ASCII.encode(content + "\n"));
				ch.force(true);
			}
			try {
				Files.move(tmp, f, StandardCopyOption.ATOMIC_MOVE);
			} catch (AtomicMoveNotSupportedException e) {
				Files.move(tmp, f);
			}
		} finally {
			Files.deleteIfExists(tmp);
		}
	}

	private static FileAttribute<Set<PosixFilePermission>> filePerms() {
		return PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(FILE_PERMS));
	}

	private static boolean posix(Path p) throws IOException {
		Path existing = p;
		while (existing != null && !Files.exists(existing)) {
			existing = existing.getParent();
		}
		return existing != null && Files.getFileStore(existing).supportsFileAttributeView("posix");
	}

	/** 逐级创建缺失目录，新建的一律 700；已有目录不改 */
	private static void createPrivateDirectories(Path dir) throws IOException {
		if (dir == null || Files.isDirectory(dir)) {
			return;
		}
		createPrivateDirectories(dir.getParent());
		try {
			if (posix(dir)) {
				Files.createDirectory(dir, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(DIR_PERMS)));
			} else {
				Files.createDirectory(dir);
				log.warn("文件系统不支持 POSIX 权限，没法把密钥目录设成 700，请自行限制访问：{}", dir);
			}
		} catch (java.nio.file.FileAlreadyExistsException race) {
			// 并发首启时别的进程刚建好
		}
	}

	/** 权限比 600 宽（组或其他用户有任何权限）就打 WARN；非 POSIX 打 WARN 说明没法确认 */
	static void warnIfTooOpen(Path file, String label) {
		try {
			if (!posix(file)) {
				log.warn("文件系统不支持 POSIX 权限，没法确认{}文件是否为 600，请自行限制访问：{}", label, file);
				return;
			}
			Set<PosixFilePermission> perms = Files.getPosixFilePermissions(file);
			Set<PosixFilePermission> extra = EnumSet.copyOf(perms);
			extra.removeAll(EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
			if (!extra.isEmpty()) {
				log.warn("{}文件权限过宽（{}），建议 chmod 600：{}", label, PosixFilePermissions.toString(perms), file);
			}
		} catch (IOException | UnsupportedOperationException e) {
			log.warn("读不到{}文件的权限，没法确认是否为 600：{}", label, file);
		}
	}
}
