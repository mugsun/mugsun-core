package com.mugsun.core.lock.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.concurrent.TimeUnit;

/**
 * 给方法加锁：同一把锁同一时刻只有一个在执行，部署多个实例也一样。
 *
 * <pre>{@code
 * // 同一个订单同时只能有一个在支付；别的请求最多等 3 秒，等不到返回「操作正在进行中，请稍后再试」
 * @DistributedLock(key = "#orderId", waitTime = 3)
 * public void pay(Long orderId) { ... }
 *
 * // 定时任务：多个实例里只让一个执行，别的实例直接跳过
 * @Scheduled(cron = "0 0 3 * * *")
 * @DistributedLock(name = "job:log-clean", onFail = LockFailAction.SKIP)
 * public void clean() { ... }
 * }</pre>
 *
 * <p>锁包在事务外面：先拿锁再开事务，事务提交以后才放锁，下一个拿到锁的一定能看到上一个提交的数据。
 *
 * <p>靠 Spring 的代理生效，所以要加在 Spring 管理的对象的 public 方法上，而且要从别的对象调进来；
 * 同一个类里自己调自己不经过代理，不会加锁。那种场合用
 * {@link com.mugsun.core.lock.template.LockTemplate}。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface DistributedLock {

	/** 锁名。不写就用「类的全名.方法名」，也就是这个方法自己一把锁 */
	String name() default "";

	/**
	 * 按什么区分不同的锁，SpEL 表达式，写法和 Spring 缓存注解的 key 一样：{@code #orderId}、{@code #req.userId}、
	 * {@code #p0}（第一个参数）、{@code #a + ':' + #b}、{@code @someBean.currentTenant()}。
	 * 算出来的值拼在锁名后面；不写就是所有调用共用一把锁。算出来是空会直接报错，不会悄悄退成一把大锁。
	 */
	String key() default "";

	/** 抢不到时最多等多久，单位见 {@link #timeUnit()}；默认 0，不等 */
	long waitTime() default 0;

	/**
	 * 租期，单位见 {@link #timeUnit()}。默认 -1：一直占到方法结束，执行期间自动续期，进程崩溃后锁在 30 秒内自己放开。
	 * 写成正数就是固定租期：到时间一定放开，不管方法有没有执行完。
	 */
	long leaseTime() default -1;

	/** {@link #waitTime()} 和 {@link #leaseTime()} 的单位，默认秒 */
	TimeUnit timeUnit() default TimeUnit.SECONDS;

	/** 没抢到锁怎么办，默认抛异常 */
	LockFailAction onFail() default LockFailAction.THROW;

	/** 没抢到锁并且 {@link #onFail()} 是抛异常时，给前端的提示语；不写用「操作正在进行中，请稍后再试」 */
	String message() default "";
}
