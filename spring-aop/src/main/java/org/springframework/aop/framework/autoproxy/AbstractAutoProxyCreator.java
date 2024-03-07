/*
 * Copyright 2002-2022 the original author or authors.
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

package org.springframework.aop.framework.autoproxy;

import java.lang.reflect.Constructor;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.aopalliance.aop.Advice;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.aop.Advisor;
import org.springframework.aop.Pointcut;
import org.springframework.aop.TargetSource;
import org.springframework.aop.framework.AopInfrastructureBean;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.framework.ProxyProcessorSupport;
import org.springframework.aop.framework.adapter.AdvisorAdapterRegistry;
import org.springframework.aop.framework.adapter.GlobalAdvisorAdapterRegistry;
import org.springframework.aop.target.SingletonTargetSource;
import org.springframework.beans.BeansException;
import org.springframework.beans.PropertyValues;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.config.SmartInstantiationAwareBeanPostProcessor;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.core.SmartClassLoader;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;

/**
 * {@link org.springframework.beans.factory.config.BeanPostProcessor} implementation
 * that wraps each eligible bean with an AOP proxy, delegating to specified interceptors
 * before invoking the bean itself.
 *
 * <p>This class distinguishes between "common" interceptors: shared for all proxies it
 * creates, and "specific" interceptors: unique per bean instance. There need not be any
 * common interceptors. If there are, they are set using the interceptorNames property.
 * As with {@link org.springframework.aop.framework.ProxyFactoryBean}, interceptors names
 * in the current factory are used rather than bean references to allow correct handling
 * of prototype advisors and interceptors: for example, to support stateful mixins.
 * Any advice type is supported for {@link #setInterceptorNames "interceptorNames"} entries.
 *
 * <p>Such auto-proxying is particularly useful if there's a large number of beans that
 * need to be wrapped with similar proxies, i.e. delegating to the same interceptors.
 * Instead of x repetitive proxy definitions for x target beans, you can register
 * one single such post processor with the bean factory to achieve the same effect.
 *
 * <p>Subclasses can apply any strategy to decide if a bean is to be proxied, e.g. by type,
 * by name, by definition details, etc. They can also return additional interceptors that
 * should just be applied to the specific bean instance. A simple concrete implementation is
 * {@link BeanNameAutoProxyCreator}, identifying the beans to be proxied via given names.
 *
 * <p>Any number of {@link TargetSourceCreator} implementations can be used to create
 * a custom target source: for example, to pool prototype objects. Auto-proxying will
 * occur even if there is no advice, as long as a TargetSourceCreator specifies a custom
 * {@link org.springframework.aop.TargetSource}. If there are no TargetSourceCreators set,
 * or if none matches, a {@link org.springframework.aop.target.SingletonTargetSource}
 * will be used by default to wrap the target bean instance.
 *
 * @author Juergen Hoeller
 * @author Rod Johnson
 * @author Rob Harrop
 * @author Sam Brannen
 * @since 13.10.2003
 * @see #setInterceptorNames
 * @see #getAdvicesAndAdvisorsForBean
 * @see BeanNameAutoProxyCreator
 * @see DefaultAdvisorAutoProxyCreator
 */
@SuppressWarnings("serial")
public abstract class AbstractAutoProxyCreator extends ProxyProcessorSupport
		implements SmartInstantiationAwareBeanPostProcessor, BeanFactoryAware {

	/**
	 * 空对象，表示不需要进行代理
	 *
	 * Convenience constant for subclasses: Return value for "do not proxy".
	 * @see #getAdvicesAndAdvisorsForBean
	 */
	@Nullable
	protected static final Object[] DO_NOT_PROXY = null;

	/**
	 * 空的数组，表示需要进行代理，但是没有解析出 Advice
	 *
	 * Convenience constant for subclasses: Return value for
	 * "proxy without additional interceptors, just the common ones".
	 * @see #getAdvicesAndAdvisorsForBean
	 */
	protected static final Object[] PROXY_WITHOUT_ADDITIONAL_INTERCEPTORS = new Object[0];


	/** Logger available to subclasses. */
	protected final Log logger = LogFactory.getLog(getClass());

	/**
	 * DefaultAdvisorAdapterRegistry 单例，Advisor适配器注册中心
	 *
	 * Default is global AdvisorAdapterRegistry.
	 */
	private AdvisorAdapterRegistry advisorAdapterRegistry = GlobalAdvisorAdapterRegistry.getInstance();

	/**
	 * Indicates whether or not the proxy should be frozen. Overridden from super
	 * to prevent the configuration from becoming frozen too early.
	 */
	/** 是否冻结代理对象 */
	private boolean freezeProxy = false;

	/** Default is no common interceptors.*/
	/** 公共的拦截器对象*/
	private String[] interceptorNames = new String[0];

	/** 是否将 `interceptorNames` 拦截器放在最前面 */
	private boolean applyCommonInterceptorsFirst = true;

	/** 自定义的 TargetSource 创建器 */
	@Nullable
	private TargetSourceCreator[] customTargetSourceCreators;

	@Nullable
	private BeanFactory beanFactory;

	/**
	 * 保存自定义 {@link TargetSource} 对象的 Bean 的名称
	 */
	private final Set<String> targetSourcedBeans = Collections.newSetFromMap(new ConcurrentHashMap<>(16));

	/**
	 * 保存提前创建代理对象的 Bean
	 * key：cacheKey（Bean 的名称或者 Class 对象）
	 * value：Bean 对象
	 *
	 * Spring AOP 的设计之初是让 Bean 在完全创建好后才完成 AOP 代理，如果出现了循环依赖，则需要提前（实例化后还未初始化）创建代理对象
	 * 那么需要先保存提前创建代理对象的 Bean，这样在后面可以防止再次创建代理对象
	 */
	private final Map<Object, Object> earlyProxyReferences = new ConcurrentHashMap<>(16);

	/**
	 * 保存代理对象的 Class 对象
	 * key：cacheKey（Bean 的名称或者 Class 对象）
	 * value：代理对象的 Class 对象（目标类的子类）
	 *
	 */
	private final Map<Object, Class<?>> proxyTypes = new ConcurrentHashMap<>(16);

	/**
	 * 保存是否需要创建代理对象的信息
	 * key：cacheKey（Bean 的名称或者 Class 对象）
	 * value：是否需要创建代理对象，false 表示不需要创建代理对象，true 表示已创建代理对象
	 */
	private final Map<Object, Boolean> advisedBeans = new ConcurrentHashMap<>(256);


	/**
	 * Set whether or not the proxy should be frozen, preventing advice
	 * from being added to it once it is created.
	 * <p>Overridden from the super class to prevent the proxy configuration
	 * from being frozen before the proxy is created.
	 */
	@Override
	public void setFrozen(boolean frozen) {
		this.freezeProxy = frozen;
	}

	@Override
	public boolean isFrozen() {
		return this.freezeProxy;
	}

	/**
	 * Specify the {@link AdvisorAdapterRegistry} to use.
	 * <p>Default is the global {@link AdvisorAdapterRegistry}.
	 * @see org.springframework.aop.framework.adapter.GlobalAdvisorAdapterRegistry
	 */
	public void setAdvisorAdapterRegistry(AdvisorAdapterRegistry advisorAdapterRegistry) {
		this.advisorAdapterRegistry = advisorAdapterRegistry;
	}

	/**
	 * Set custom {@code TargetSourceCreators} to be applied in this order.
	 * If the list is empty, or they all return null, a {@link SingletonTargetSource}
	 * will be created for each bean.
	 * <p>Note that TargetSourceCreators will kick in even for target beans
	 * where no advices or advisors have been found. If a {@code TargetSourceCreator}
	 * returns a {@link TargetSource} for a specific bean, that bean will be proxied
	 * in any case.
	 * <p>{@code TargetSourceCreators} can only be invoked if this post processor is used
	 * in a {@link BeanFactory} and its {@link BeanFactoryAware} callback is triggered.
	 * @param targetSourceCreators the list of {@code TargetSourceCreators}.
	 * Ordering is significant: The {@code TargetSource} returned from the first matching
	 * {@code TargetSourceCreator} (that is, the first that returns non-null) will be used.
	 */
	public void setCustomTargetSourceCreators(TargetSourceCreator... targetSourceCreators) {
		this.customTargetSourceCreators = targetSourceCreators;
	}

	/**
	 * Set the common interceptors. These must be bean names in the current factory.
	 * They can be of any advice or advisor type Spring supports.
	 * <p>If this property isn't set, there will be zero common interceptors.
	 * This is perfectly valid, if "specific" interceptors such as matching
	 * Advisors are all we want.
	 */
	public void setInterceptorNames(String... interceptorNames) {
		this.interceptorNames = interceptorNames;
	}

	/**
	 * Set whether the common interceptors should be applied before bean-specific ones.
	 * Default is "true"; else, bean-specific interceptors will get applied first.
	 */
	public void setApplyCommonInterceptorsFirst(boolean applyCommonInterceptorsFirst) {
		this.applyCommonInterceptorsFirst = applyCommonInterceptorsFirst;
	}

	@Override
	public void setBeanFactory(BeanFactory beanFactory) {
		this.beanFactory = beanFactory;
	}

	/**
	 * Return the owning {@link BeanFactory}.
	 * May be {@code null}, as this post-processor doesn't need to belong to a bean factory.
	 */
	@Nullable
	protected BeanFactory getBeanFactory() {
		return this.beanFactory;
	}


	@Override
	@Nullable
	public Class<?> predictBeanType(Class<?> beanClass, String beanName) {
		if (this.proxyTypes.isEmpty()) {
			return null;
		}
		Object cacheKey = getCacheKey(beanClass, beanName);
		return this.proxyTypes.get(cacheKey);
	}

	@Override
	@Nullable
	public Constructor<?>[] determineCandidateConstructors(Class<?> beanClass, String beanName) {
		return null;
	}

	/**
	 * 该方法对早期对象（提前暴露的对象，已实例化还未初始化）进行处理
	 * 参考 {@link org.springframework.beans.factory.support.AbstractAutowireCapableBeanFactory#getEarlyBeanReference }
	 *
	 * @param bean     the raw bean instance
	 * @param beanName the name of the bean
	 * @return 早期对象（可能是一个代理对象）
	 */
	@Override
	public Object getEarlyBeanReference(Object bean, String beanName) {
		// <1> 获取这个 Bean 的缓存 Key，默认为 Bean 的名称，没有则取其对应的 Class 对象
		Object cacheKey = getCacheKey(bean.getClass(), beanName);
		/*
		 * <2> 将当前 Bean 保存至 earlyProxyReferences 集合（早期的代理应用对象）
		 * 也就是说当这个 Bean 出现循环依赖了，在实例化后就创建了代理对象（如果有必要）
		 */
		this.earlyProxyReferences.put(cacheKey, bean);
		// <3> 为这个 Bean 创建代理对象（如果有必要的话）
		return wrapIfNecessary(bean, beanName, cacheKey);
	}

	/**
	 * 在加载 Bean 的过程中，Bean 实例化的前置处理
	 * 如果返回的不是 null 则不会进行后续的加载过程，也就是说这个方法用于获取一个 Bean 对象
	 * 通常这里用于创建 AOP 代理对象，返回的对象不为 null，则会继续调用下面的 {@link this#postProcessAfterInitialization} 方法进行初始化后置处理
	 * 参考 {@link org.springframework.beans.factory.support.AbstractAutowireCapableBeanFactory#resolveBeforeInstantiation}
	 *
	 * @param beanClass the class of the bean to be instantiated
	 * @param beanName  the name of the bean
	 * @return 代理对象或者空对象
	 */
	@Override
	public Object postProcessBeforeInstantiation(Class<?> beanClass, String beanName) {
		// <1> 获取这个 Bean 的缓存 Key，默认为 Bean 的名称，没有则取其对应的 Class 对象
		Object cacheKey = getCacheKey(beanClass, beanName);

		// <2> 如果没有 beanName 或者没有自定义生成 TargetSource
		if (!StringUtils.hasLength(beanName) // 没有 beanName
				|| !this.targetSourcedBeans.contains(beanName)) { // 没有自定义生成 TargetSource
			/*
			 * <2.1> 已创建代理对象（或不需要创建），则直接返回 null，进行后续的 Bean 加载过程
			 */
			if (this.advisedBeans.containsKey(cacheKey)) {
				return null;
			}
			/*
			 * <2.2>不需要创建代理对象，则直接返回 null，进行后续的 Bean 加载过程
			 */
			if (isInfrastructureClass(beanClass) // 如果是 Spring 内部的 Bean（Advice、Pointcut、Advisor 或者 AopInfrastructureBean 标记接口）
					|| shouldSkip(beanClass, beanName)) { // 应该跳过
				// 将这个 Bean 不需要创建代理对象的结果保存起来
				this.advisedBeans.put(cacheKey, Boolean.FALSE);
				return null;
			}
		}

		// Create proxy here if we have a custom TargetSource.
		// Suppresses unnecessary default instantiation of the target bean:
		// The TargetSource will handle target instances in a custom fashion.
		/*
		 * <3> 通过自定义 TargetSourceCreator 创建自定义 TargetSource 对象
		 * 默认没有 TargetSourceCreator，所以这里通常都是返回 null
		 */
		TargetSource targetSource = getCustomTargetSource(beanClass, beanName);
		/*
		 * <4> 如果 TargetSource 不为空，表示需要创建代理对象
		 */
		if (targetSource != null) {
			// <4.1> 将当前 beanName 保存至集合，表示这个 Bean 已自定义生成 TargetSource 对象
			if (StringUtils.hasLength(beanName)) {
				this.targetSourcedBeans.add(beanName);
			}
			// <4.2> 获取能够应用到当前 Bean 的所有 Advisor（已根据 @Order 排序）
			Object[] specificInterceptors = getAdvicesAndAdvisorsForBean(beanClass, beanName, targetSource);
			// <4.3> 创建代理对象，JDK 动态代理或者 CGLIB 动态代理
			Object proxy = createProxy(beanClass, beanName, specificInterceptors, targetSource);
			// <4.4> 将代理对象的 Class 对象（目标类的子类）保存
			this.proxyTypes.put(cacheKey, proxy.getClass());
			// <4.5> 返回代理对象
			return proxy;
		}

		// <5> 否则，直接返回 null，进行后续的 Bean 加载过程
		return null;
	}

	@Override
	public PropertyValues postProcessProperties(PropertyValues pvs, Object bean, String beanName) {
		return pvs;  // skip postProcessPropertyValues
	}

	/**
	 * Create a proxy with the configured interceptors if the bean is
	 * identified as one to proxy by the subclass.
	 * @see #getAdvicesAndAdvisorsForBean
	 */
	/**
	 * 在加载 Bean 的过程中，Bean 初始化的后置处理
	 * 参考 {@link org.springframework.beans.factory.support.AbstractAutowireCapableBeanFactory#initializeBean(String, Object, RootBeanDefinition)}
	 */
	@Override
	public Object postProcessAfterInitialization(@Nullable Object bean, String beanName) {
		// <1> 如果 bean 不为空则进行接下来的处理
		if (bean != null) {
			// <1.1> 获取这个 Bean 的缓存 Key，默认为 Bean 的名称，没有则取其对应的 Class 对象
			Object cacheKey = getCacheKey(bean.getClass(), beanName);
			/*
			 * <1.2> 移除 `earlyProxyReferences` 集合中保存的当前 Bean 对象（如果有的话）
			 * 如果 earlyProxyReferences 集合中没有当前 Bean 对象，表示在前面没有创建代理对象
			 */
			if (this.earlyProxyReferences.remove(cacheKey) != bean) {
				// 这里尝试为这个 Bean 创建一个代理对象（如果有必要的话）
				return wrapIfNecessary(bean, beanName, cacheKey);
			}
		}
		// <2> 直接返回 bean 对象
		return bean;
	}


	/**
	 * Build a cache key for the given bean class and bean name.
	 * <p>Note: As of 4.2.3, this implementation does not return a concatenated
	 * class/name String anymore but rather the most efficient cache key possible:
	 * a plain bean name, prepended with {@link BeanFactory#FACTORY_BEAN_PREFIX}
	 * in case of a {@code FactoryBean}; or if no bean name specified, then the
	 * given bean {@code Class} as-is.
	 * @param beanClass the bean class
	 * @param beanName the bean name
	 * @return the cache key for the given class and name
	 */
	protected Object getCacheKey(Class<?> beanClass, @Nullable String beanName) {
		if (StringUtils.hasLength(beanName)) {
			return (FactoryBean.class.isAssignableFrom(beanClass) ?
					BeanFactory.FACTORY_BEAN_PREFIX + beanName : beanName);
		}
		else {
			return beanClass;
		}
	}

	/**
	 * Wrap the given bean if necessary, i.e. if it is eligible for being proxied.
	 * @param bean the raw bean instance
	 * @param beanName the name of the bean
	 * @param cacheKey the cache key for metadata access
	 * @return a proxy wrapping the bean, or the raw bean instance as-is
	 */
	protected Object wrapIfNecessary(Object bean, String beanName, Object cacheKey) {
		/*
		 * <1> 如果当前 Bean 已经创建过自定义 TargetSource 对象
		 * 表示在上面的**实例化前置处理**中已经创建代理对象，那么直接返回这个对象
		 */
		if (StringUtils.hasLength(beanName) && this.targetSourcedBeans.contains(beanName)) {
			return bean;
		}
		// 判断是否不应该代理这个 bean
		// <2> `advisedBeans` 保存了这个 Bean 没有必要创建代理对象，则直接返回
		if (Boolean.FALSE.equals(this.advisedBeans.get(cacheKey))) {
			return bean;
		}
		/**
		 * 判断是否是一些 InfrastructureClass 或者是否应该跳过这个 bean。
		 * * 所谓 InfrastructureClass 就是指 Advice/PointCut/Advisor 等接口的实现类。
		 * * shouldSkip 默认实现为返回 false,由于是 protected 方法，子类可以覆盖。
		 * */
		/*
		 * <3> 不需要创建代理对象，则直接返回当前 Bean
		 */
		if (isInfrastructureClass(bean.getClass()) // 如果是 Spring 内部的 Bean（Advice、Pointcut、Advisor 或者 AopInfrastructureBean 标记接口）
				|| shouldSkip(bean.getClass(), beanName)) { // 应该跳过
			// 将这个 Bean 不需要创建代理对象的结果保存起来
			this.advisedBeans.put(cacheKey, Boolean.FALSE);
			return bean;
		}

		// Create proxy if we have advice.
		// <4> 获取能够应用到当前 Bean 的所有 Advisor（已根据 @Order 排序）
		Object[] specificInterceptors = getAdvicesAndAdvisorsForBean(bean.getClass(), beanName, null);
		// <5> 如果有 Advisor，则进行下面的动态代理创建过程
		if (specificInterceptors != DO_NOT_PROXY) {
			// <5.1> 将这个 Bean 已创建代理对象的结果保存至 `advisedBeans`
			this.advisedBeans.put(cacheKey, Boolean.TRUE);
			// <5.2> 创建代理对象，JDK 动态代理或者 CGLIB 动态代理
			// 这里传入的是 SingletonTargetSource 对象，可获取代理对象的目标对象（当前 Bean）
			Object proxy = createProxy(
					bean.getClass(), beanName, specificInterceptors, new SingletonTargetSource(bean));
			// <5.3> 将代理对象的 Class 对象（目标类的子类）保存
			this.proxyTypes.put(cacheKey, proxy.getClass());
			// <5.4> 返回代理对象
			return proxy;
		}

		// <6> 否则，将这个 Bean 不需要创建代理对象的结果保存起来
		this.advisedBeans.put(cacheKey, Boolean.FALSE);
		// <7> 返回这个 Bean 对象
		return bean;
	}

	/**
	 * Return whether the given bean class represents an infrastructure class
	 * that should never be proxied.
	 * <p>The default implementation considers Advices, Advisors and
	 * AopInfrastructureBeans as infrastructure classes.
	 * @param beanClass the class of the bean
	 * @return whether the bean represents an infrastructure class
	 * @see org.aopalliance.aop.Advice
	 * @see org.springframework.aop.Advisor
	 * @see org.springframework.aop.framework.AopInfrastructureBean
	 * @see #shouldSkip
	 */
	protected boolean isInfrastructureClass(Class<?> beanClass) {
		boolean retVal = Advice.class.isAssignableFrom(beanClass) ||
				Pointcut.class.isAssignableFrom(beanClass) ||
				Advisor.class.isAssignableFrom(beanClass) ||
				AopInfrastructureBean.class.isAssignableFrom(beanClass);
		if (retVal && logger.isTraceEnabled()) {
			logger.trace("Did not attempt to auto-proxy infrastructure class [" + beanClass.getName() + "]");
		}
		return retVal;
	}

	/**
	 * Subclasses should override this method to return {@code true} if the
	 * given bean should not be considered for auto-proxying by this post-processor.
	 * <p>Sometimes we need to be able to avoid this happening, e.g. if it will lead to
	 * a circular reference or if the existing target instance needs to be preserved.
	 * This implementation returns {@code false} unless the bean name indicates an
	 * "original instance" according to {@code AutowireCapableBeanFactory} conventions.
	 * @param beanClass the class of the bean
	 * @param beanName the name of the bean
	 * @return whether to skip the given bean
	 * @see org.springframework.beans.factory.config.AutowireCapableBeanFactory#ORIGINAL_INSTANCE_SUFFIX
	 */
	protected boolean shouldSkip(Class<?> beanClass, String beanName) {
		return AutoProxyUtils.isOriginalInstance(beanName, beanClass);
	}

	/**
	 * Create a target source for bean instances. Uses any TargetSourceCreators if set.
	 * Returns {@code null} if no custom TargetSource should be used.
	 * <p>This implementation uses the "customTargetSourceCreators" property.
	 * Subclasses can override this method to use a different mechanism.
	 * @param beanClass the class of the bean to create a TargetSource for
	 * @param beanName the name of the bean
	 * @return a TargetSource for this bean
	 * @see #setCustomTargetSourceCreators
	 */
	@Nullable
	protected TargetSource getCustomTargetSource(Class<?> beanClass, String beanName) {
		// We can't create fancy target sources for directly registered singletons.
		if (this.customTargetSourceCreators != null &&
				this.beanFactory != null && this.beanFactory.containsBean(beanName)) {
			for (TargetSourceCreator tsc : this.customTargetSourceCreators) {
				// 通过 TargetSourceCreator 获取 `beanName` 的自定义 TargetSource
				TargetSource ts = tsc.getTargetSource(beanClass, beanName);
				if (ts != null) {
					// Found a matching TargetSource.
					if (logger.isTraceEnabled()) {
						logger.trace("TargetSourceCreator [" + tsc +
								"] found custom TargetSource for bean with name '" + beanName + "'");
					}
					return ts;
				}
			}
		}

		// No custom TargetSource found.
		return null;
	}

	/**
	 * Create an AOP proxy for the given bean.
	 * @param beanClass the class of the bean
	 * @param beanName the name of the bean
	 * @param specificInterceptors the set of interceptors that is
	 * specific to this bean (may be empty, but not null)
	 * @param targetSource the TargetSource for the proxy,
	 * already pre-configured to access the bean
	 * @return the AOP proxy for the bean
	 * @see #buildAdvisors
	 */
	protected Object createProxy(Class<?> beanClass, @Nullable String beanName,
			@Nullable Object[] specificInterceptors, TargetSource targetSource) {

		if (this.beanFactory instanceof ConfigurableListableBeanFactory) {
			// 为目标 Bean 的 BeanDefinition 对象设置一个属性
			// org.springframework.aop.framework.autoproxy.AutoProxyUtils.originalTargetClass -> 目标 Bean 的 Class 对象
			AutoProxyUtils.exposeTargetClass((ConfigurableListableBeanFactory) this.beanFactory, beanName, beanClass);
		}

		// <1> 创建一个代理工厂
		ProxyFactory proxyFactory = new ProxyFactory();
		// <2> 复制当前 ProxyConfig 的一些属性（例如 proxyTargetClass、exposeProxy）
		proxyFactory.copyFrom(this);

		/**
		 * <3> 判断是否类代理，也就是是否开启 CGLIB 代理
		 * 默认配置下为 `false`，参考 {@link org.springframework.context.annotation.EnableAspectJAutoProxy}
		 */
		if (proxyFactory.isProxyTargetClass()) {
			// Explicit handling of JDK proxy targets and lambdas (for introduction advice scenarios)
			if (Proxy.isProxyClass(beanClass) || ClassUtils.isLambdaClass(beanClass)) {
				// Must allow for introductions; can't just set interfaces to the proxy's interfaces only.
				for (Class<?> ifc : beanClass.getInterfaces()) {
					proxyFactory.addInterface(ifc);
				}
			}
		}
		else {
			// No proxyTargetClass flag enforced, let's apply our default checks...
			/*
			 * <3.1> 如果这个 Bean 配置了进行类代理，则设置为 `proxyTargetClass` 为 `true`
			 */
			if (shouldProxyTargetClass(beanClass, beanName)) {
				proxyFactory.setProxyTargetClass(true);
			}
			else {
				/*
				 * <3.2> 检测当前 Bean 实现的接口是否包含可代理的接口
				 * 如没有实现，则将 `proxyTargetClass` 设为 `true`，表示需要进行 CGLIB 提升
				 */
				evaluateProxyInterfaces(beanClass, proxyFactory);
			}
		}

		/*
		 * <4> 对入参的 Advisor 进一步处理，因为其中可能还存在 Advice 类型，需要将他们包装成 DefaultPointcutAdvisor 对象
		 * 如果配置了 `interceptorNames` 拦截器，也会添加进来
		 */
		Advisor[] advisors = buildAdvisors(beanName, specificInterceptors);
		// <5> 代理工厂添加 Advisor 数组
		proxyFactory.addAdvisors(advisors);
		// <6> 代理工厂设置 TargetSource 对象
		proxyFactory.setTargetSource(targetSource);
		// <7> 对 ProxyFactory 进行加工处理，抽象方法，目前没有子类实现
		customizeProxyFactory(proxyFactory);

		proxyFactory.setFrozen(this.freezeProxy);
		// <7> 对 ProxyFactory 进行加工处理，抽象方法，目前没有子类实现
		if (advisorsPreFiltered()) {
			// 设置 `preFiltered` 为 `true`
			// 这样 Advisor 们就不会根据 ClassFilter 进行过滤了，而直接通过 MethodMatcher 判断是否处理被拦截方法
			proxyFactory.setPreFiltered(true);
		}

		// Use original ClassLoader if bean class not locally loaded in overriding class loader
		ClassLoader classLoader = getProxyClassLoader();
		if (classLoader instanceof SmartClassLoader && classLoader != beanClass.getClassLoader()) {
			classLoader = ((SmartClassLoader) classLoader).getOriginalClassLoader();
		}
		// <9> 通过 ProxyFactory 代理工厂创建代理对象
		return proxyFactory.getProxy(classLoader);
	}

	/**
	 * Determine whether the given bean should be proxied with its target class rather than its interfaces.
	 * <p>Checks the {@link AutoProxyUtils#PRESERVE_TARGET_CLASS_ATTRIBUTE "preserveTargetClass" attribute}
	 * of the corresponding bean definition.
	 * @param beanClass the class of the bean
	 * @param beanName the name of the bean
	 * @return whether the given bean should be proxied with its target class
	 * @see AutoProxyUtils#shouldProxyTargetClass
	 */
	protected boolean shouldProxyTargetClass(Class<?> beanClass, @Nullable String beanName) {
		return (this.beanFactory instanceof ConfigurableListableBeanFactory &&
				AutoProxyUtils.shouldProxyTargetClass((ConfigurableListableBeanFactory) this.beanFactory, beanName));
	}

	/**
	 * Return whether the Advisors returned by the subclass are pre-filtered
	 * to match the bean's target class already, allowing the ClassFilter check
	 * to be skipped when building advisors chains for AOP invocations.
	 * <p>Default is {@code false}. Subclasses may override this if they
	 * will always return pre-filtered Advisors.
	 * @return whether the Advisors are pre-filtered
	 * @see #getAdvicesAndAdvisorsForBean
	 * @see org.springframework.aop.framework.Advised#setPreFiltered
	 */
	protected boolean advisorsPreFiltered() {
		return false;
	}

	/**
	 * Determine the advisors for the given bean, including the specific interceptors
	 * as well as the common interceptor, all adapted to the Advisor interface.
	 * @param beanName the name of the bean
	 * @param specificInterceptors the set of interceptors that is
	 * specific to this bean (may be empty, but not null)
	 * @return the list of Advisors for the given bean
	 */
	protected Advisor[] buildAdvisors(@Nullable String beanName, @Nullable Object[] specificInterceptors) {
		// Handle prototypes correctly...
		// <1> 将配置的 `interceptorNames` 转换成 Advisor 类型（默认没有）
		Advisor[] commonInterceptors = resolveInterceptorNames();

		// <2> 将 commonInterceptors 与 specificInterceptors 放入一个集合
		List<Object> allInterceptors = new ArrayList<>();
		if (specificInterceptors != null) {
			if (specificInterceptors.length > 0) {
				// specificInterceptors may equal PROXY_WITHOUT_ADDITIONAL_INTERCEPTORS
				allInterceptors.addAll(Arrays.asList(specificInterceptors));
			}
			if (commonInterceptors.length > 0) {
				// 是否添加至最前面（默认为 true）
				if (this.applyCommonInterceptorsFirst) {
					allInterceptors.addAll(0, Arrays.asList(commonInterceptors));
				}
				else {
					allInterceptors.addAll(Arrays.asList(commonInterceptors));
				}
			}
		}
		if (logger.isTraceEnabled()) {
			int nrOfCommonInterceptors = commonInterceptors.length;
			int nrOfSpecificInterceptors = (specificInterceptors != null ? specificInterceptors.length : 0);
			logger.trace("Creating implicit proxy for bean '" + beanName + "' with " + nrOfCommonInterceptors +
					" common interceptors and " + nrOfSpecificInterceptors + " specific interceptors");
		}

		Advisor[] advisors = new Advisor[allInterceptors.size()];
		/*
		 * <3> 遍历 `specificInterceptors` 数组
		 */
		for (int i = 0; i < allInterceptors.size(); i++) {
			// <3.1> 将不是 Advisor 类型的 Advice 或者 MethodInterceptor 包装成 DefaultPointcutAdvisor 对象
			advisors[i] = this.advisorAdapterRegistry.wrap(allInterceptors.get(i));
		}
		// <4> 返回构建好的 Advisor 数组
		return advisors;
	}

	/**
	 * Resolves the specified interceptor names to Advisor objects.
	 * @see #setInterceptorNames
	 */
	private Advisor[] resolveInterceptorNames() {
		BeanFactory bf = this.beanFactory;
		ConfigurableBeanFactory cbf = (bf instanceof ConfigurableBeanFactory ? (ConfigurableBeanFactory) bf : null);
		List<Advisor> advisors = new ArrayList<>();
		for (String beanName : this.interceptorNames) {
			if (cbf == null || !cbf.isCurrentlyInCreation(beanName)) {
				Assert.state(bf != null, "BeanFactory required for resolving interceptor names");
				Object next = bf.getBean(beanName);
				advisors.add(this.advisorAdapterRegistry.wrap(next));
			}
		}
		return advisors.toArray(new Advisor[0]);
	}

	/**
	 * Subclasses may choose to implement this: for example,
	 * to change the interfaces exposed.
	 * <p>The default implementation is empty.
	 * @param proxyFactory a ProxyFactory that is already configured with
	 * TargetSource and interfaces and will be used to create the proxy
	 * immediately after this method returns
	 */
	protected void customizeProxyFactory(ProxyFactory proxyFactory) {
	}


	/**
	 * Return whether the given bean is to be proxied, what additional
	 * advices (e.g. AOP Alliance interceptors) and advisors to apply.
	 * @param beanClass the class of the bean to advise
	 * @param beanName the name of the bean
	 * @param customTargetSource the TargetSource returned by the
	 * {@link #getCustomTargetSource} method: may be ignored.
	 * Will be {@code null} if no custom target source is in use.
	 * @return an array of additional interceptors for the particular bean;
	 * or an empty array if no additional interceptors but just the common ones;
	 * or {@code null} if no proxy at all, not even with the common interceptors.
	 * See constants DO_NOT_PROXY and PROXY_WITHOUT_ADDITIONAL_INTERCEPTORS.
	 * @throws BeansException in case of errors
	 * @see #DO_NOT_PROXY
	 * @see #PROXY_WITHOUT_ADDITIONAL_INTERCEPTORS
	 */
	@Nullable
	protected abstract Object[] getAdvicesAndAdvisorsForBean(Class<?> beanClass, String beanName,
			@Nullable TargetSource customTargetSource) throws BeansException;

}
