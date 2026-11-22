package com.mugsun.core.web.jackson;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

/**
 * 过渡期默认值：HTTP 消息转换、HTTP 编解码、WebSocket 消息都用 Jackson 2。
 * <p>Spring Boot 4 默认用 Jackson 3 读写请求体和响应体，而本基座的 {@link SafeNumberModule}、接口加解密和各应用的
 * 自定义序列化器都还是 Jackson 2 的写法，Jackson 3 不认。不改这个默认值的话接口不报错，但 Long 不再转成字符串，
 * 前端拿到的大整数会丢精度。
 * <p>这组默认值排在所有配置来源的最后，应用自己写了同名配置项就以应用的为准。全部换到 Jackson 3 以后删掉这个类。
 */
public class Jackson2PreferenceEnvironmentPostProcessor implements EnvironmentPostProcessor {

	/** 属性源名字：排查配置来源时（/actuator/env 等）能看出这几项是基座给的默认值 */
	public static final String PROPERTY_SOURCE_NAME = "mugsunJackson2Defaults";

	private static final Map<String, Object> DEFAULTS = Map.of(
		"spring.http.converters.preferred-json-mapper", "jackson2",
		"spring.http.codecs.preferred-json-mapper", "jackson2",
		"spring.websocket.messaging.preferred-json-mapper", "jackson2");

	@Override
	public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
		if (environment.getPropertySources().contains(PROPERTY_SOURCE_NAME)) {
			return;
		}
		environment.getPropertySources().addLast(new MapPropertySource(PROPERTY_SOURCE_NAME, DEFAULTS));
	}
}
