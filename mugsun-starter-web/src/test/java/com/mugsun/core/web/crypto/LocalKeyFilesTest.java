package com.mugsun.core.web.crypto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class LocalKeyFilesTest {

	@TempDir
	Path tmp;

	@Test
	@DisplayName("并发首启：16 个线程同时 loadOrCreate，只生成一次，所有人拿到同一内容")
	void concurrentFirstStart() throws Exception {
		Path f = tmp.resolve("home/k.key");
		AtomicInteger generated = new AtomicInteger();
		CountDownLatch go = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(16);
		try {
			List<Future<LocalKeyFiles.Loaded>> fs = new ArrayList<>();
			for (int i = 0; i < 16; i++) {
				Callable<LocalKeyFiles.Loaded> c = () -> {
					go.await();
					return LocalKeyFiles.loadOrCreate(f, () -> "k" + generated.incrementAndGet(), v -> v.startsWith("k"), "测试密钥");
				};
				fs.add(pool.submit(c));
			}
			go.countDown();
			Set<String> contents = new HashSet<>();
			int created = 0;
			for (var fu : fs) {
				var l = fu.get();
				contents.add(l.content());
				created += l.created() ? 1 : 0;
			}
			assertThat(generated.get()).isEqualTo(1);
			assertThat(created).isEqualTo(1);
			assertThat(contents).hasSize(1);
		} finally {
			pool.shutdownNow();
		}
	}

	@Test
	@DisplayName("权限过宽只 WARN 不拒绝：644 的文件照常读出")
	void tooOpenStillLoads() throws Exception {
		Path f = tmp.resolve("k.key");
		Files.writeString(f, "kabc\n");
		if (Files.getFileStore(f).supportsFileAttributeView("posix")) {
			Files.setPosixFilePermissions(f, PosixFilePermissions.fromString("rw-r--r--"));
		}
		var l = LocalKeyFiles.loadOrCreate(f, () -> "knew", v -> v.startsWith("k"), "测试密钥");
		assertThat(l.content()).isEqualTo("kabc");
		assertThat(l.created()).isFalse();
		assertThat(l.toString()).doesNotContain("kabc");
	}

	@Test
	@DisplayName("mugsun.home 为空时落到 ~/.mugsun；配置了就用配置值")
	void resolveHome() {
		assertThat(LocalKeyFiles.resolveHome("")).isEqualTo(Path.of(System.getProperty("user.home"), ".mugsun").toAbsolutePath().normalize());
		assertThat(LocalKeyFiles.resolveHome(" " + tmp + " ")).isEqualTo(tmp.toAbsolutePath().normalize());
	}

	@Test
	@DisplayName("容器检测：/.dockerenv 或 cgroup 含 docker/kubepods")
	void containerDetection() throws Exception {
		Path none = tmp.resolve("none");
		Path cg = tmp.resolve("cgroup");
		Files.writeString(cg, "0::/\n");
		assertThat(LocalKeyFiles.inContainer(none, cg)).isFalse();
		Files.writeString(cg, "12:memory:/kubepods/burstable/pod1\n");
		assertThat(LocalKeyFiles.inContainer(none, cg)).isTrue();
		Path de = tmp.resolve(".dockerenv");
		Files.createFile(de);
		assertThat(LocalKeyFiles.inContainer(de, none)).isTrue();
	}

	@Test
	@DisplayName("密钥文件或 .lock 是符号链接：拒绝启动")
	void symlinkRejected() throws Exception {
		Path real = tmp.resolve("real.key");
		Files.writeString(real, "kabc\n");
		Path home = tmp.resolve("h");
		Files.createDirectories(home);
		Files.createSymbolicLink(home.resolve("k.key"), real);
		org.assertj.core.api.Assertions.assertThatThrownBy(() ->
			LocalKeyFiles.loadOrCreate(home.resolve("k.key"), () -> "knew", v -> v.startsWith("k"), "测试密钥"))
			.isInstanceOf(IllegalStateException.class).hasMessageContaining("符号链接");
		Path home2 = tmp.resolve("h2");
		Files.createDirectories(home2);
		Files.createSymbolicLink(home2.resolve("k.key.lock"), real);
		org.assertj.core.api.Assertions.assertThatThrownBy(() ->
			LocalKeyFiles.loadOrCreate(home2.resolve("k.key"), () -> "knew", v -> v.startsWith("k"), "测试密钥"))
			.isInstanceOf(IllegalStateException.class).hasMessageContaining("符号链接");
		assertThat(Files.readString(real)).isEqualTo("kabc\n");
	}

	@Test
	@DisplayName("目录组/其他可写：打 WARN，照常启动")
	void unsafeDirWarns() throws Exception {
		Path home = tmp.resolve("open");
		Files.createDirectories(home);
		org.junit.jupiter.api.Assumptions.assumeTrue(Files.getFileStore(home).supportsFileAttributeView("posix"));
		Files.setPosixFilePermissions(home, PosixFilePermissions.fromString("rwxrwxrwx"));
		ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(LocalKeyFiles.class);
		var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
		appender.start();
		logger.addAppender(appender);
		try {
			var l = LocalKeyFiles.loadOrCreate(home.resolve("k.key"), () -> "knew", v -> v.startsWith("k"), "测试密钥");
			assertThat(l.created()).isTrue();
			assertThat(appender.list).anyMatch(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN
				&& e.getFormattedMessage().contains("可写"));
		} finally {
			logger.detachAppender(appender);
		}
	}
}
