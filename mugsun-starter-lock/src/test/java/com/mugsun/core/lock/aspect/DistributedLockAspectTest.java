package com.mugsun.core.lock.aspect;

import com.mugsun.core.lock.annotation.DistributedLock;
import com.mugsun.core.lock.annotation.LockFailAction;
import com.mugsun.core.lock.client.LocalLockClient;
import com.mugsun.core.lock.client.LockClient;
import com.mugsun.core.lock.config.MugsunLockAutoConfiguration;
import com.mugsun.core.lock.exception.LockFailedException;
import com.mugsun.core.lock.template.LockHandle;
import com.mugsun.core.lock.template.LockTemplate;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

@DisplayName("加锁注解")
class DistributedLockAspectTest {

	private static final Runnable NOTHING = () -> {
	};

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(AopAutoConfiguration.class, MugsunLockAutoConfiguration.class))
		.withUserConfiguration(TestBeans.class);

	private final ExecutorService other = Executors.newSingleThreadExecutor();

	@AfterEach
	void tearDown() {
		other.shutdownNow();
	}

	@Test
	@DisplayName("不写锁名：用「类全名.方法名」，key 算出来的值拼在后面；不写租期：按默认 30 秒上锁；执行完释放")
	void defaultNameAndLease() {
		runner.run(context -> {
			OrderService service = context.getBean(OrderService.class);
			RecordingLockClient client = context.getBean(RecordingLockClient.class);

			assertThat(service.pay(7L)).isEqualTo("paid:7");

			assertThat(client.keys).containsExactly("lock:" + OrderService.class.getName() + ".pay:7");
			assertThat(client.ttls).containsExactly(Duration.ofSeconds(30));
			assertThat(client.held).isEmpty();
		});
	}

	@Test
	@DisplayName("key 表达式：参数名、#p0、对象属性、#root、调用别的 Bean 都能用")
	void keyExpressions() {
		runner.run(context -> {
			OrderService service = context.getBean(OrderService.class);
			RecordingLockClient client = context.getBean(RecordingLockClient.class);

			service.refund(7L, new RefundRequest("damaged"));
			service.byRoot("A1");
			service.perTenant(3L);

			assertThat(client.keys).containsExactly("lock:order:refund:7:damaged", "lock:root:byRoot:A1",
				"lock:tenant:t1:3");
		});
	}

	@Test
	@DisplayName("锁被占着：默认抛 LockFailedException，方法不执行；换一个参数是另一把锁，不受影响")
	void throwsWhenBusy() {
		runner.run(context -> {
			OrderService service = context.getBean(OrderService.class);
			List<Object> seen = new ArrayList<>();
			service.onInside(() -> {
				service.onInside(NOTHING);
				seen.add(inOtherThread(() -> catchThrowable(() -> service.pay(7L))));
				seen.add(inOtherThread(() -> service.pay(8L)));
			});

			assertThat(service.pay(7L)).isEqualTo("paid:7");

			assertThat(seen.get(0)).isInstanceOf(LockFailedException.class);
			assertThat((LockFailedException) seen.get(0)).hasMessage(LockTemplate.DEFAULT_FAIL_MESSAGE);
			assertThat(seen.get(1)).isEqualTo("paid:8");
			assertThat(service.calls()).isEqualTo(2);
		});
	}

	@Test
	@DisplayName("waitTime 和单位生效：等够了时间才报，提示语用注解上写的")
	void waitsAndUsesCustomMessage() {
		runner.run(context -> {
			OrderService service = context.getBean(OrderService.class);
			LockTemplate template = context.getBean(LockTemplate.class);

			try (LockHandle held = template.tryLock("import").orElseThrow()) {
				long start = System.nanoTime();
				Throwable thrown = inOtherThread(() -> catchThrowable(service::importData));
				long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

				assertThat(thrown).isInstanceOf(LockFailedException.class).hasMessage("有人正在导入，请稍后");
				assertThat(elapsedMillis).isGreaterThanOrEqualTo(300);
			}
			assertThat(service.calls()).isZero();
		});
	}

	@Test
	@DisplayName("leaseTime 写了正数：按固定租期上锁，单位默认是秒")
	void fixedLease() {
		runner.run(context -> {
			context.getBean(OrderService.class).fixedLease();

			assertThat(context.getBean(RecordingLockClient.class).ttls).containsExactly(Duration.ofSeconds(5));
		});
	}

	@Test
	@DisplayName("onFail = SKIP：抢不到直接返回，不执行也不报错；有返回值的返回 null")
	void skipsWhenBusy() {
		runner.run(context -> {
			OrderService service = context.getBean(OrderService.class);
			LockTemplate template = context.getBean(LockTemplate.class);

			try (LockHandle held = template.tryLock("job:clean").orElseThrow()) {
				inOtherThread(() -> {
					service.clean();
					return null;
				});
				assertThat(service.calls()).isZero();
			}
			service.clean();
			assertThat(service.calls()).isEqualTo(1);

			try (LockHandle held = template.tryLock("job:count").orElseThrow()) {
				assertThat(inOtherThread(service::count)).isNull();
			}
			assertThat(service.count()).isEqualTo(7);
		});
	}

	@Test
	@DisplayName("onFail = SKIP 用在返回基本类型的方法上：第一次调用就报错，不等到抢不到锁才暴露")
	void skipOnPrimitiveReturnFails() {
		runner.run(context -> {
			OrderService service = context.getBean(OrderService.class);

			assertThatThrownBy(service::primitive).isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("onFail = SKIP");
			assertThat(service.calls()).isZero();
		});
	}

	@Test
	@DisplayName("key 表达式算出来是空（比如参数名写错）：直接报错，方法不执行，也不加锁")
	void nullKeyFails() {
		runner.run(context -> {
			OrderService service = context.getBean(OrderService.class);

			assertThatThrownBy(() -> service.typo(1L)).isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("#idd");
			assertThat(service.calls()).isZero();
			assertThat(context.getBean(RecordingLockClient.class).keys).isEmpty();
		});
	}

	@Test
	@DisplayName("方法抛异常：异常原样抛出，锁照样释放")
	void releasesOnException() {
		runner.run(context -> {
			OrderService service = context.getBean(OrderService.class);

			assertThatThrownBy(service::boom).isInstanceOf(IllegalStateException.class).hasMessage("业务出错");
			assertThat(context.getBean(RecordingLockClient.class).held).isEmpty();
		});
	}

	@Test
	@DisplayName("同一个线程里嵌套调用同一把锁：可以重入，不会自己挡住自己")
	void reentrant() {
		runner.run(context -> {
			OrderService service = context.getBean(OrderService.class);
			RecordingLockClient client = context.getBean(RecordingLockClient.class);
			service.onInside(() -> {
				service.onInside(NOTHING);
				service.clean();
			});

			service.clean();

			assertThat(service.calls()).isEqualTo(2);
			assertThat(client.keys).containsExactly("lock:job:clean");
			assertThat(client.held).isEmpty();
		});
	}

	@Test
	@DisplayName("锁包在最里层的切面外面（事务就排在最里层）：进去之前已经拿到锁，出来以后才放")
	void wrapsInnermostAspect() {
		runner.run(context -> {
			context.getBean(OrderService.class).pay(7L);

			assertThat(context.getBean(InnermostAspect.class).lockHeldBeforeAndAfter).containsExactly(true, true);
			assertThat(context.getBean(RecordingLockClient.class).held).isEmpty();
		});
	}

	private <T> T inOtherThread(Callable<T> task) {
		try {
			return other.submit(task).get(30, TimeUnit.SECONDS);
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	@Configuration(proxyBeanMethods = false)
	static class TestBeans {

		@Bean
		RecordingLockClient lockClient() {
			return new RecordingLockClient();
		}

		@Bean
		OrderService orderService() {
			return new OrderService();
		}

		@Bean
		Tenants tenants() {
			return new Tenants();
		}

		@Bean
		InnermostAspect innermostAspect(RecordingLockClient client) {
			return new InnermostAspect(client);
		}
	}

	/** 被代理的业务类。测试只通过方法访问它：直接读写代理对象的字段碰不到真正的对象 */
	static class OrderService {

		private final AtomicInteger calls = new AtomicInteger();

		private volatile Runnable inside = NOTHING;

		public int calls() {
			return calls.get();
		}

		/** 让下一次进入加锁方法时，在锁里面执行一段代码 */
		public void onInside(Runnable action) {
			this.inside = action;
		}

		@DistributedLock(key = "#orderId")
		public String pay(Long orderId) {
			calls.incrementAndGet();
			inside.run();
			return "paid:" + orderId;
		}

		@DistributedLock(name = "order:refund", key = "#p0 + ':' + #request.reason")
		public void refund(Long orderId, RefundRequest request) {
			calls.incrementAndGet();
		}

		@DistributedLock(name = "root", key = "#root.methodName + ':' + #root.args[0]")
		public void byRoot(String code) {
			calls.incrementAndGet();
		}

		@DistributedLock(name = "tenant", key = "@tenants.current() + ':' + #id")
		public void perTenant(Long id) {
			calls.incrementAndGet();
		}

		@DistributedLock(name = "import", waitTime = 300, timeUnit = TimeUnit.MILLISECONDS, message = "有人正在导入，请稍后")
		public void importData() {
			calls.incrementAndGet();
		}

		@DistributedLock(name = "fixed", leaseTime = 5)
		public void fixedLease() {
			calls.incrementAndGet();
		}

		@DistributedLock(name = "job:clean", onFail = LockFailAction.SKIP)
		public void clean() {
			calls.incrementAndGet();
			inside.run();
		}

		@DistributedLock(name = "job:count", onFail = LockFailAction.SKIP)
		public Integer count() {
			return 7;
		}

		@DistributedLock(name = "job:primitive", onFail = LockFailAction.SKIP)
		public int primitive() {
			calls.incrementAndGet();
			return 1;
		}

		@DistributedLock(key = "#idd")
		public void typo(Long id) {
			calls.incrementAndGet();
		}

		@DistributedLock(name = "boom")
		public void boom() {
			throw new IllegalStateException("业务出错");
		}
	}

	record RefundRequest(String reason) {
	}

	static class Tenants {

		public String current() {
			return "t1";
		}
	}

	/** 没有指定顺序的切面排在最里层，和 Spring 事务的默认位置一样 */
	@Aspect
	static class InnermostAspect {

		final List<Boolean> lockHeldBeforeAndAfter = new CopyOnWriteArrayList<>();

		private final RecordingLockClient client;

		InnermostAspect(RecordingLockClient client) {
			this.client = client;
		}

		@Around("bean(orderService) && execution(* pay(..))")
		public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
			lockHeldBeforeAndAfter.add(!client.held.isEmpty());
			try {
				return joinPoint.proceed();
			} finally {
				lockHeldBeforeAndAfter.add(!client.held.isEmpty());
			}
		}
	}

	/** 记下每次抢到的锁名和租期，以及现在还占着哪些锁 */
	static class RecordingLockClient implements LockClient {

		final List<String> keys = new CopyOnWriteArrayList<>();

		final List<Duration> ttls = new CopyOnWriteArrayList<>();

		final Set<String> held = ConcurrentHashMap.newKeySet();

		private final LocalLockClient delegate = new LocalLockClient();

		@Override
		public boolean tryAcquire(String key, String token, Duration ttl) {
			if (!delegate.tryAcquire(key, token, ttl)) {
				return false;
			}
			keys.add(key);
			ttls.add(ttl);
			held.add(key);
			return true;
		}

		@Override
		public boolean release(String key, String token) {
			boolean released = delegate.release(key, token);
			if (released) {
				held.remove(key);
			}
			return released;
		}

		@Override
		public boolean renew(String key, String token, Duration ttl) {
			return delegate.renew(key, token, ttl);
		}
	}
}
