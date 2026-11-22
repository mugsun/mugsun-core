package com.mugsun.core.lock.aspect;

import com.mugsun.core.lock.annotation.DistributedLock;
import com.mugsun.core.lock.annotation.LockFailAction;
import com.mugsun.core.lock.exception.LockFailedException;
import com.mugsun.core.lock.template.LockHandle;
import com.mugsun.core.lock.template.LockTemplate;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.expression.MethodBasedEvaluationContext;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.Ordered;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.core.annotation.Order;
import org.springframework.expression.BeanResolver;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.util.ObjectUtils;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 让 {@link DistributedLock} 生效：算出锁名，拿到锁再执行方法，执行完（包括抛异常）释放。
 *
 * <p>顺序定在 {@link #ORDER}：比事务（默认排在最里层）靠外，所以是先拿锁再开事务、事务提交以后才放锁；
 * 又比大多数切面靠里，权限校验、防重复提交这些会先执行，被它们拦下的请求不会占锁。
 * 自己的切面要排在锁的外面或里面，参照这个常量定顺序。
 */
@Aspect
@Order(DistributedLockAspect.ORDER)
public class DistributedLockAspect {

	/** 切面顺序：数字越小越靠外 */
	public static final int ORDER = Ordered.LOWEST_PRECEDENCE - 1000;

	private static final Logger log = LoggerFactory.getLogger(DistributedLockAspect.class);

	private final LockTemplate lockTemplate;

	private final BeanResolver beanResolver;

	private final ExpressionParser parser = new SpelExpressionParser();

	private final ParameterNameDiscoverer parameterNames = new DefaultParameterNameDiscoverer();

	private final Map<String, Expression> expressions = new ConcurrentHashMap<>();

	public DistributedLockAspect(LockTemplate lockTemplate) {
		this(lockTemplate, null);
	}

	/**
	 * @param beanResolver 让 key 表达式里能写 {@code @someBean.method()}；传 null 就不支持这种写法
	 */
	public DistributedLockAspect(LockTemplate lockTemplate, BeanResolver beanResolver) {
		this.lockTemplate = lockTemplate;
		this.beanResolver = beanResolver;
	}

	@Around(value = "@annotation(lock)", argNames = "joinPoint,lock")
	public Object around(ProceedingJoinPoint joinPoint, DistributedLock lock) throws Throwable {
		Method method = targetMethod(joinPoint);
		boolean skipOnFail = lock.onFail() == LockFailAction.SKIP;
		Class<?> returnType = method.getReturnType();
		if (skipOnFail && returnType.isPrimitive() && returnType != void.class) {
			// 每次调用都报，不等到真的抢不到锁那一次才暴露
			throw new IllegalStateException(method + " 的返回值是基本类型 " + returnType
				+ "，没抢到锁时没有空值可以返回，不能用 onFail = SKIP；把返回值改成 void 或包装类型");
		}
		String key = lockKey(joinPoint, method, lock);
		Duration waitTime = lock.waitTime() > 0 ? Duration.of(lock.waitTime(), lock.timeUnit().toChronoUnit())
			: LockTemplate.NO_WAIT;
		Duration leaseTime = lock.leaseTime() > 0 ? Duration.of(lock.leaseTime(), lock.timeUnit().toChronoUnit())
			: null;
		Optional<LockHandle> handle = lockTemplate.tryLock(key, waitTime, leaseTime);
		if (handle.isEmpty()) {
			if (skipOnFail) {
				log.debug("没抢到锁 {}，跳过 {}", key, method);
				return null;
			}
			throw new LockFailedException(key,
				lock.message().isEmpty() ? LockTemplate.DEFAULT_FAIL_MESSAGE : lock.message());
		}
		try (LockHandle held = handle.get()) {
			return joinPoint.proceed();
		}
	}

	private String lockKey(ProceedingJoinPoint joinPoint, Method method, DistributedLock lock) {
		String name = lock.name().isEmpty() ? method.getDeclaringClass().getName() + "." + method.getName()
			: lock.name();
		if (lock.key().isEmpty()) {
			return name;
		}
		Object[] args = joinPoint.getArgs();
		MethodBasedEvaluationContext context = new MethodBasedEvaluationContext(
			new LockExpressionRoot(method, args, joinPoint.getTarget()), method, args, parameterNames);
		if (beanResolver != null) {
			context.setBeanResolver(beanResolver);
		}
		Object value = expressions.computeIfAbsent(lock.key(), parser::parseExpression).getValue(context);
		if (value == null) {
			// 参数名写错时 SpEL 不报错、直接算出空；不拦的话所有调用会悄悄共用一把锁
			throw new IllegalStateException("@DistributedLock 的 key 表达式 \"" + lock.key() + "\" 在 " + method
				+ " 上算出来是空：检查表达式里的参数名有没有写错、传进来的值是不是空");
		}
		return name + ":" + ObjectUtils.nullSafeToString(value);
	}

	private static Method targetMethod(ProceedingJoinPoint joinPoint) {
		Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
		Object target = joinPoint.getTarget();
		if (target == null) {
			return method;
		}
		// 换成实现类上的方法：接口代理时签名里是接口方法，拿不到实现类的参数名
		return AopUtils.getMostSpecificMethod(method, AopProxyUtils.ultimateTargetClass(target));
	}
}
