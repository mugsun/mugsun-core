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
}
