/*
 * Copyright 2002-2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.aop.framework;

import java.io.Serializable;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;

import org.aopalliance.intercept.MethodInvocation;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.aop.AopInvocationException;
import org.springframework.aop.RawTargetAccess;
import org.springframework.aop.TargetSource;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.DecoratingProxy;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;

/**
 * JDK-based {@link AopProxy} implementation for the Spring AOP framework,
 * based on JDK {@link java.lang.reflect.Proxy dynamic proxies}.
 *
 * <p>Creates a dynamic proxy, implementing the interfaces exposed by
 * the AopProxy. Dynamic proxies <i>cannot</i> be used to proxy methods
 * defined in classes, rather than interfaces.
 *
 * <p>Objects of this type should be obtained through proxy factories,
 * configured by an {@link AdvisedSupport} class. This class is internal
 * to Spring's AOP framework and need not be used directly by client code.
 *
 * <p>Proxies created using this class will be thread-safe if the
 * underlying (target) class is thread-safe.
 *
 * <p>Proxies are serializable so long as all Advisors (including Advices
 * and Pointcuts) and the TargetSource are serializable.
 *
 * @author Rod Johnson
 * @author Juergen Hoeller
 * @author Rob Harrop
 * @author Dave Syer
 * @author Sergey Tsypanov
 * @see java.lang.reflect.Proxy
 * @see AdvisedSupport
 * @see ProxyFactory
 */
final class JdkDynamicAopProxy implements AopProxy, InvocationHandler, Serializable {

	/** use serialVersionUID from Spring 1.2 for interoperability. */
	private static final long serialVersionUID = 5531744639992436476L;


	/*
	 * NOTE: We could avoid the code duplication between this class and the CGLIB
	 * proxies by refactoring "invoke" into a template method. However, this approach
	 * adds at least 10% performance overhead versus a copy-paste solution, so we sacrifice
	 * elegance for performance. (We have a good test suite to ensure that the different
	 * proxies behave the same :-)
	 * This way, we can also more easily take advantage of minor optimizations in each class.
	 */

	/** We use a static Log to avoid serialization issues. */
	private static final Log logger = LogFactory.getLog(JdkDynamicAopProxy.class);

	/** Config used to configure this proxy. */
	/** 代理对象的配置信息，例如保存了 TargetSource 目标类来源、能够应用于目标类的所有 Advisor */
	private final AdvisedSupport advised;

	private final Class<?>[] proxiedInterfaces;

	/**
	 * Is the {@link #equals} method defined on the proxied interfaces?
	 */
	/** 目标对象是否重写了 equals 方法 */
	private boolean equalsDefined;

	/**
	 * Is the {@link #hashCode} method defined on the proxied interfaces?
	 */
	/** 目标对象是否重写了 hashCode 方法 */
	private boolean hashCodeDefined;


	/**
	 * Construct a new JdkDynamicAopProxy for the given AOP configuration.
	 * @param config the AOP configuration as AdvisedSupport object
	 * @throws AopConfigException if the config is invalid. We try to throw an informative
	 * exception in this case, rather than let a mysterious failure happen later.
	 */
	public JdkDynamicAopProxy(AdvisedSupport config) throws AopConfigException {
		Assert.notNull(config, "AdvisedSupport must not be null");
		if (config.getAdvisorCount() == 0 // 没有 Advisor，表示没有任何动作
				&& config.getTargetSource() == AdvisedSupport.EMPTY_TARGET_SOURCE) { // 没有来源
			throw new AopConfigException("No advisors and no TargetSource specified");
		}
		this.advised = config;
		// <1> 获取需要代理的接口（目标类实现的接口，会加上 Spring 内部的几个接口，例如 SpringProxy）
		this.proxiedInterfaces = AopProxyUtils.completeProxiedInterfaces(this.advised, true);
		// <2> 判断目标类是否重写了 `equals` 或者 `hashCode` 方法
		// 没有重写在拦截到这两个方法的时候，会调用当前类的实现
		findDefinedEqualsAndHashCodeMethods(this.proxiedInterfaces);
	}


	@Override
	public Object getProxy() {
		return getProxy(ClassUtils.getDefaultClassLoader());
	}

	/**
	 * 获取代理类要实现的接口,除了 Advised 对象中配置的,还会加上 SpringProxy, Advised(opaque=false)
	 * 检查上面得到的接口中有没有定义 equals 或者 hashcode 的接口
	 * 调用 Proxy.newProxyInstance 创建代理对象
	 */
	@Override
	public Object getProxy(@Nullable ClassLoader classLoader) {
		if (logger.isTraceEnabled()) {
			logger.trace("Creating JDK dynamic proxy: " + this.advised.getTargetSource());
		}
		// <3> 调用 JDK 的 Proxy#newProxyInstance(..) 方法创建代理对象
		// 传入的参数就是当前 ClassLoader 类加载器、需要代理的接口、InvocationHandler 实现类
		return Proxy.newProxyInstance(classLoader, this.proxiedInterfaces, this);
	}

	/**
	 * Finds any {@link #equals} or {@link #hashCode} method that may be defined
	 * on the supplied set of interfaces.
	 * @param proxiedInterfaces the interfaces to introspect
	 */
	private void findDefinedEqualsAndHashCodeMethods(Class<?>[] proxiedInterfaces) {
		for (Class<?> proxiedInterface : proxiedInterfaces) {
			Method[] methods = proxiedInterface.getDeclaredMethods();
			for (Method method : methods) {
				if (AopUtils.isEqualsMethod(method)) {
					this.equalsDefined = true;
				}
				if (AopUtils.isHashCodeMethod(method)) {
					this.hashCodeDefined = true;
				}
				if (this.equalsDefined && this.hashCodeDefined) {
					return;
				}
			}
		}
	}


	/**
	 * Implementation of {@code InvocationHandler.invoke}.
	 * <p>Callers will see exactly the exception thrown by the target,
	 * unless a hook method throws an exception.
	 */
	@Override
	@Nullable
	public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
		Object oldProxy = null;
		boolean setProxyContext = false;

		// <1> 获取目标类的 TargetSource 对象，用于获取目标类
		TargetSource targetSource = this.advised.targetSource;
		Object target = null;

		try {
			// <2> 如果拦截到下面几种情况的方法，则需要进行额外处理

			// <2.1> 如果拦截到的方法是 `equals`，且目标类没有重写，则调用当前类重写的 `equals` 方法
			if (!this.equalsDefined && AopUtils.isEqualsMethod(method)) {
				// The target does not implement the equals(Object) method itself.
				return equals(args[0]);
			}
			// <2.2> 否则，如果拦截到的方法是 `hashCode`，且目标类没有重写，则调用当前类重写的 `hashCode` 方法
			else if (!this.hashCodeDefined && AopUtils.isHashCodeMethod(method)) {
				// The target does not implement the hashCode() method itself.
				return hashCode();
			}
			// <2.3> 否则，如果拦截到的是 DecoratingProxy 中的方法，则通过 AopProxyUtils 工具类计算出目标类的 Class 对象
			else if (method.getDeclaringClass() == DecoratingProxy.class) {
				// There is only getDecoratedClass() declared -> dispatch to proxy config.
				return AopProxyUtils.ultimateTargetClass(this.advised);
			}
			// <2.4> 否则，如果拦截到的是 Advised 中的方法，则通过 AopUtils 工具类调用该方法（反射）
			else if (!this.advised.opaque && method.getDeclaringClass().isInterface() &&
					method.getDeclaringClass().isAssignableFrom(Advised.class)) {
				// Service invocations on ProxyConfig with the proxy config...
				return AopUtils.invokeJoinpointUsingReflection(this.advised, method, args);
			}

			Object retVal;

			// <3> 如果 `expose-proxy` 属性为 `true`，则需要暴露当前代理对象
			if (this.advised.exposeProxy) {
				// Make invocation available if necessary.
				// <3.1> 向 AopContext 中设置代理对象，并记录 ThreadLocal 之前存放的代理对象
				// 这样一来，在 Advice 或者被拦截方法中可以通过 AopContext 获取到这个代理对象
				oldProxy = AopContext.setCurrentProxy(proxy);
				// <3.2> 标记这个代理对象被暴露了
				setProxyContext = true;
			}

			// Get as late as possible to minimize the time we "own" the target,
			// in case it comes from a pool.
			// <4> 获取目标对象，以及它的 Class 对象
			target = targetSource.getTarget();
			Class<?> targetClass = (target != null ? target.getClass() : null);

			// Get the interception chain for this method.
			// <5> 获取能够应用于该方法的所有拦截器（有序）
			// 不同的 AspectJ 根据 @Order 排序
			// 同一个 AspectJ 中的 Advice 排序：AspectJAfterThrowingAdvice > AfterReturningAdviceInterceptor > AspectJAfterAdvice > AspectJAroundAdvice > MethodBeforeAdviceInterceptor
			List<Object> chain = this.advised.getInterceptorsAndDynamicInterceptionAdvice(method, targetClass);

			// Check whether we have any advice. If we don't, we can fallback on direct
			// reflective invocation of the target, and avoid creating a MethodInvocation.
			// 如果没有可以应用到此方法的通知(Interceptor)，此直接反射调用 Method.invoke(target, args)
			// <6> 如果拦截器链为空，则直接执行目标方法
			if (chain.isEmpty()) {
				// We can skip creating a MethodInvocation: just invoke the target directly
				// Note that the final invoker must be an InvokerInterceptor so we know it does
				// nothing but a reflective operation on the target, and no hot swapping or fancy proxying.
				// <6.1> 参数适配处理
				Object[] argsToUse = AopProxyUtils.adaptArgumentsIfNecessary(method, args);
				// <6.2> 执行目标方法（反射），并获取返回结果
				retVal = AopUtils.invokeJoinpointUsingReflection(target, method, argsToUse);
			}
			// <7> 否则，需要根据拦截器链去执行目标方法
			else {
				// We need to create a method invocation...
				// <7.1> 创建一个方法调用器，并将前面第 `5` 步获取到的拦截器链传入其中
				// 该对象就是 Joinpoint 对象
				MethodInvocation invocation =
						new ReflectiveMethodInvocation(proxy, target, method, args, targetClass, chain);
				// Proceed to the joinpoint through the interceptor chain.
				// <7.2> 执行目标方法，以及所有的 MethodInterceptor 方法拦截器（Advice 通知器），并获取返回结果
				retVal = invocation.proceed();
			}

			// Massage return value if necessary.
			// <8> 获取目标方法返回值类型
			Class<?> returnType = method.getReturnType();
			// <9> 如果需要返回代理对象
			if (retVal != null // 返回值不为空
					&& retVal == target // 返回值就是当前目标对象
					&& returnType != Object.class // 返回值类型不是 Object 类型
					&& returnType.isInstance(proxy) // 返回值类型就是代理对象的类型
					&& !RawTargetAccess.class.isAssignableFrom(method.getDeclaringClass())) {
				// Special case: it returned "this" and the return type of the method
				// is type-compatible. Note that we can't help if the target sets
				// a reference to itself in another returned object.
				// 将当前代理对象作为返回结果
				retVal = proxy;
			}
			// <10> 否则，如果返回值类型为原始类型（基本类型，不能为空）且方法的返回类型不是 Void，如果返回值为空则抛出异常
			else if (retVal == null && returnType != Void.TYPE && returnType.isPrimitive()) {
				throw new AopInvocationException(
						"Null return value from advice does not match primitive return type for: " + method);
			}
			// <11> 返回 `retVal` 返回结果
			return retVal;
		}
		finally {
			// <12> 如果目标对象不为空，且 TargetSource 不是静态的（表示每次都得返回一个新的目标对象）
			// 那么需要释放当前获取到的目标对象，通常情况下我们的单例 Bean 对应的都是 SingletonTargetSource，不需要释放
			if (target != null && !targetSource.isStatic()) {
				// Must have come from TargetSource.
				targetSource.releaseTarget(target);
			}
			// <13> 如果暴露了当前代理对象，则需要将之前的代理对象重新设置到 ThreadLocal 中
			if (setProxyContext) {
				// Restore old proxy.
				AopContext.setCurrentProxy(oldProxy);
			}
		}
	}


	/**
	 * Equality means interfaces, advisors and TargetSource are equal.
	 * <p>The compared object may be a JdkDynamicAopProxy instance itself
	 * or a dynamic proxy wrapping a JdkDynamicAopProxy instance.
	 */
	@Override
	public boolean equals(@Nullable Object other) {
		if (other == this) {
			return true;
		}
		if (other == null) {
			return false;
		}

		JdkDynamicAopProxy otherProxy;
		if (other instanceof JdkDynamicAopProxy) {
			otherProxy = (JdkDynamicAopProxy) other;
		}
		else if (Proxy.isProxyClass(other.getClass())) {
			InvocationHandler ih = Proxy.getInvocationHandler(other);
			if (!(ih instanceof JdkDynamicAopProxy)) {
				return false;
			}
			otherProxy = (JdkDynamicAopProxy) ih;
		}
		else {
			// Not a valid comparison...
			return false;
		}

		// If we get here, otherProxy is the other AopProxy.
		return AopProxyUtils.equalsInProxy(this.advised, otherProxy.advised);
	}

	/**
	 * Proxy uses the hash code of the TargetSource.
	 */
	@Override
	public int hashCode() {
		return JdkDynamicAopProxy.class.hashCode() * 13 + this.advised.getTargetSource().hashCode();
	}

}
