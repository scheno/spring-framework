package org.springframework.aop;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

/**
 * @author shenchen
 * @since 2024/3/7 21:42
 */
@Aspect
@Configuration
@EnableAspectJAutoProxy
public class AspectConfiguration {

	@Pointcut("execution(public String org.springframework.aop.EchoService.execute(..))")
	public void pointcut() {

	}

	@Before("pointcut()")
	public void before() {
		System.out.println("before...");
	}

	@Around("pointcut()")
	public void proceed(ProceedingJoinPoint pjp) throws Throwable {
		System.out.println("before around...");
		pjp.proceed();
		System.out.println("after around...");
	}

}
