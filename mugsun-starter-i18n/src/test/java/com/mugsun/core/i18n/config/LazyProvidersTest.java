package com.mugsun.core.i18n.config;

import com.mugsun.core.i18n.config.MugsunI18nAutoConfiguration.LazyProviders;
import com.mugsun.core.i18n.source.MessageProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("到查文案时才去容器里找文案来源")
@ExtendWith(OutputCaptureExtension.class)
class LazyProvidersTest {

	/** 文案来源建不起来时日志里的那句提醒 */
	private static final String WARNING = "没能准备好";

	private static final MessageProvider PROVIDER = (code, locale) -> "from provider";

	private final DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();

	/** 自己拨的钟（纳秒） */
	private final AtomicLong now = new AtomicLong(1_000L);

	/** 文案来源被创建了几次，失败的也算 */
	private final AtomicInteger attempts = new AtomicInteger();

	private final LazyProviders providers = new LazyProviders(beanFactory.getBeanProvider(MessageProvider.class),
		now::get);

	@Test
	@DisplayName("找到以后记住，不会每次查文案都再找一遍")
	void resolvedOnce() {
		// 每次去容器里取都会新建一个，这样找了几遍能数出来
		BeanDefinition definition = definition(() -> PROVIDER);
		definition.setScope(BeanDefinition.SCOPE_PROTOTYPE);
		beanFactory.registerBeanDefinition("provider", definition);

		List<MessageProvider> first = providers.get();

		assertThat(first).containsExactly(PROVIDER);
		assertThat(providers.get()).isSameAs(first);
		assertThat(providers.get()).isSameAs(first);
		assertThat(attempts).hasValue(1);
	}

	@Test
	@DisplayName("没有注册文案来源：给空的，不算出错")
	void none(CapturedOutput output) {
		assertThat(providers.get()).isEmpty();
		assertThat(output).doesNotContain(WARNING);
	}

	@Test
	@DisplayName("文案来源建不起来：给空的并提醒一次，一分钟内不再去建；过了一分钟再试，建好以后照常用它")
	void failureBacksOff(CapturedOutput output) {
		AtomicBoolean broken = new AtomicBoolean(true);
		beanFactory.registerBeanDefinition("provider", definition(() -> {
			if (broken.get()) {
				throw new IllegalStateException("数据库连不上");
			}
			return PROVIDER;
		}));

		for (int i = 0; i < 50; i++) {
			assertThat(providers.get()).isEmpty();
		}
		assertThat(attempts).hasValue(1);
		assertThat(warnings(output)).isEqualTo(1);
		assertThat(output).contains("数据库连不上");

		// 差一点不到一分钟：还不试
		now.addAndGet(LazyProviders.RETRY_INTERVAL_NANOS - 1);
		assertThat(providers.get()).isEmpty();
		assertThat(attempts).hasValue(1);

		// 满一分钟：再试一次，还是不行，再提醒一次
		now.addAndGet(1);
		assertThat(providers.get()).isEmpty();
		assertThat(providers.get()).isEmpty();
		assertThat(attempts).hasValue(2);
		assertThat(warnings(output)).isEqualTo(2);

		// 数据库恢复了：下一次重试就能建好，之后一直用它
		broken.set(false);
		assertThat(providers.get()).isEmpty();
		now.addAndGet(LazyProviders.RETRY_INTERVAL_NANOS);
		assertThat(providers.get()).containsExactly(PROVIDER);
		assertThat(providers.get()).containsExactly(PROVIDER);
		assertThat(attempts).hasValue(3);
		assertThat(warnings(output)).isEqualTo(2);
	}

	@Test
	@DisplayName("文案来源在自己创建的过程中来找：那一次给空的，不算出错、不用等一分钟，建好以后马上能用")
	void whileBeingCreated(CapturedOutput output) {
		AtomicReference<List<MessageProvider>> seenWhileCreating = new AtomicReference<>();
		beanFactory.registerBeanDefinition("provider", definition(() -> {
			seenWhileCreating.set(providers.get());
			return PROVIDER;
		}));

		assertThat(providers.get()).containsExactly(PROVIDER);
		assertThat(seenWhileCreating.get()).isEmpty();
		assertThat(attempts).hasValue(1);
		assertThat(output).doesNotContain(WARNING);
	}

	@Test
	@DisplayName("容器正在关闭时来找：给空的，不算出错，也不会在这时候去新建文案来源")
	void whileClosing(CapturedOutput output) {
		AtomicReference<List<MessageProvider>> seenWhileClosing = new AtomicReference<>();
		beanFactory.registerBeanDefinition("provider", definition(() -> PROVIDER));
		beanFactory.registerDisposableBean("closing", () -> seenWhileClosing.set(providers.get()));

		beanFactory.destroySingletons();

		assertThat(seenWhileClosing.get()).isEmpty();
		assertThat(attempts).hasValue(0);
		assertThat(output).doesNotContain(WARNING);
	}

	private BeanDefinition definition(Supplier<MessageProvider> supplier) {
		return BeanDefinitionBuilder.genericBeanDefinition(MessageProvider.class, () -> {
			attempts.incrementAndGet();
			return supplier.get();
		}).getBeanDefinition();
	}

	private static int warnings(CapturedOutput output) {
		return StringUtils.countOccurrencesOf(output.toString(), WARNING);
	}
}
