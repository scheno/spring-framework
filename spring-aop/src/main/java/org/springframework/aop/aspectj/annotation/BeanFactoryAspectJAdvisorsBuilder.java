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

package org.springframework.aop.aspectj.annotation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.aspectj.lang.reflect.PerClauseKind;

import org.springframework.aop.Advisor;
import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;

/**
 * Helper for retrieving @AspectJ beans from a BeanFactory and building
 * Spring Advisors based on them, for use with auto-proxying.
 *
 * @author Juergen Hoeller
 * @since 2.0.2
 * @see AnnotationAwareAspectJAutoProxyCreator
 */
public class BeanFactoryAspectJAdvisorsBuilder {

	/**
	 * 当前 IoC 容器，DefaultListableBeanFactory
	 */
	private final ListableBeanFactory beanFactory;

	/**
	 * Advisor 工厂，用于解析 @AspectJ 注解的 Bean 中的 Advisor
	 */
	private final AspectJAdvisorFactory advisorFactory;

	/**
	 * 用于缓存带有 @AspectJ 注解的 Bean 的名称
	 */
	@Nullable
	private volatile List<String> aspectBeanNames;

	/**
	 * 缓存 @AspectJ 注解的单例 Bean 中解析出来的 Advisor
	 * key：带有 @AspectJ 注解的 beanName
	 * value：其内部解析出来的 Advisor 集合
	 */
	private final Map<String, List<Advisor>> advisorsCache = new ConcurrentHashMap<>();

	/**
	 * 缓存 @AspectJ 注解的非单例 Bean 的元数据实例构建工厂
	 * key：带有 @AspectJ 注解的 beanName（非单例）
	 * value：对应的元数据工厂对象
	 */
	private final Map<String, MetadataAwareAspectInstanceFactory> aspectFactoryCache = new ConcurrentHashMap<>();


	/**
	 * Create a new BeanFactoryAspectJAdvisorsBuilder for the given BeanFactory.
	 * @param beanFactory the ListableBeanFactory to scan
	 */
	public BeanFactoryAspectJAdvisorsBuilder(ListableBeanFactory beanFactory) {
		this(beanFactory, new ReflectiveAspectJAdvisorFactory(beanFactory));
	}

	/**
	 * Create a new BeanFactoryAspectJAdvisorsBuilder for the given BeanFactory.
	 * @param beanFactory the ListableBeanFactory to scan
	 * @param advisorFactory the AspectJAdvisorFactory to build each Advisor with
	 */
	public BeanFactoryAspectJAdvisorsBuilder(ListableBeanFactory beanFactory, AspectJAdvisorFactory advisorFactory) {
		Assert.notNull(beanFactory, "ListableBeanFactory must not be null");
		Assert.notNull(advisorFactory, "AspectJAdvisorFactory must not be null");
		this.beanFactory = beanFactory;
		this.advisorFactory = advisorFactory;
	}


	/**
	 * Look for AspectJ-annotated aspect beans in the current bean factory,
	 * and return to a list of Spring AOP Advisors representing them.
	 * <p>Creates a Spring Advisor for each AspectJ advice method.
	 * @return the list of {@link org.springframework.aop.Advisor} beans
	 * @see #isEligibleBean
	 */
	public List<Advisor> buildAspectJAdvisors() {
		// <1> 从缓存中获取所有带有 @AspectJ 注解的 Bean，保存至 `aspectNames` 集合中
		List<String> aspectNames = this.aspectBeanNames;

		// <2> 缓存中没有，则对当前对象加锁再判断缓存中是否有数据
		if (aspectNames == null) {
			synchronized (this) {
				aspectNames = this.aspectBeanNames;
				// <3> 还是没有缓存，则进行接下来的处理
				if (aspectNames == null) {
					List<Advisor> advisors = new ArrayList<>();
					aspectNames = new ArrayList<>();
					// <3.1> 获取当前 IoC 容器中所有的 Bean 的名称集合
					String[] beanNames = BeanFactoryUtils.beanNamesForTypeIncludingAncestors(
							this.beanFactory, Object.class, true, false);
					// <3.2> 遍历所有的 Bean 的名称，进行处理
					for (String beanName : beanNames) {
						// <3.3> 判断这个 Bean 是否有资格，默认都为 true
						if (!isEligibleBean(beanName)) {
							// 如果没有资格则跳过
							continue;
						}
						// We must be careful not to instantiate beans eagerly as in this case they
						// would be cached by the Spring container but would not have been weaved.
						// <3.4> 获取这个 Bean 的 Class 对象，如果为空则跳过
						Class<?> beanType = this.beanFactory.getType(beanName, false);
						if (beanType == null) {
							continue;
						}
						// <3.5> 如果这个 Bean 带有 @Aspect 注解，且没有以 `ajc$` 开头的字段，那么进行接下来的解析过程
						if (this.advisorFactory.isAspect(beanType)) {
							// <3.5.1>  将这个 Bean 的名称保存至 `aspectNames` 集合中
							aspectNames.add(beanName);
							AspectMetadata amd = new AspectMetadata(beanType, beanName);
							// <3.5.2> 判断 @AspectJ 注解的类别是否为 `singleton`，默认空的情况就是这个
							if (amd.getAjType().getPerClause().getKind() == PerClauseKind.SINGLETON) {
								MetadataAwareAspectInstanceFactory factory =
										new BeanFactoryAspectInstanceFactory(this.beanFactory, beanName);
								// <3.5.2.1> 解析这个 Bean 中带有 @Before|@After|@Around|@AfterReturning|@AfterThrowing 注解的方法
								// 会解析成对应的 InstantiationModelAwarePointcutAdvisorImpl 对象（PointcutAdvisor）
								List<Advisor> classAdvisors = this.advisorFactory.getAdvisors(factory);
								// <3.5.2.2> 如果这个 Bean 是单例模式，则将解析出来的 Advisor 全部缓存至 `advisorsCache` 中
								if (this.beanFactory.isSingleton(beanName)) {
									this.advisorsCache.put(beanName, classAdvisors);
								}
								// <3.5.2.3> 否则，将这个 Bean 对应的 MetadataAwareAspectInstanceFactory（AspectJ 元数据实例构建工厂）缓存至 `aspectFactoryCache` 中
								else {
									this.aspectFactoryCache.put(beanName, factory);
								}
								// <3.5.2.4> 将解析出来的 Advisor 添加至 `advisors` 中
								advisors.addAll(classAdvisors);
							}
							// <3.5.3> 否则，这个 AspectJ 不是单例模式，不能将解析出来的 Advisor 缓存，其他的处理过程都和上面一样
							else {
								// Per target or per this.
								if (this.beanFactory.isSingleton(beanName)) {
									throw new IllegalArgumentException("Bean with name '" + beanName +
											"' is a singleton, but aspect instantiation model is not singleton");
								}
								MetadataAwareAspectInstanceFactory factory =
										new PrototypeAspectInstanceFactory(this.beanFactory, beanName);
								// 将这个 Bean 对应的 MetadataAwareAspectInstanceFactory（AspectJ 元数据实例构建工厂）缓存至 `aspectFactoryCache` 中
								this.aspectFactoryCache.put(beanName, factory);
								// 解析出这个 Bean 中所有的 Advisor，并添加至 `advisors` 中
								advisors.addAll(this.advisorFactory.getAdvisors(factory));
							}
						}
					}
					// <3.6> 对 `aspectNames` 进行缓存
					this.aspectBeanNames = aspectNames;
					// <3.7> 返回所有 AspectJ 的所有的 Advisor 对象们
					return advisors;
				}
			}
		}

		if (aspectNames.isEmpty()) {
			return Collections.emptyList();
		}
		/*
		 * <4> 否则，遍历缓存中的 AspectJ 的 beanName
		 */
		List<Advisor> advisors = new ArrayList<>();
		for (String aspectName : aspectNames) {
			// <4.1> 尝试从 `advisorsCache` 缓存中获取这个 beanName 对应的所有 Advisor 们，并添加至 `advisors` 中
			List<Advisor> cachedAdvisors = this.advisorsCache.get(aspectName);
			if (cachedAdvisors != null) {
				advisors.addAll(cachedAdvisors);
			}
			// <4.2> `advisorsCache` 缓存中没有，
			// 则根据 `aspectFactoryCache` 缓存中对应的 MetadataAwareAspectInstanceFactory（AspectJ 元数据实例构建工厂）解析出所有的 Advisor 们，并添加至 `advisors` 中
			else {
				MetadataAwareAspectInstanceFactory factory = this.aspectFactoryCache.get(aspectName);
				advisors.addAll(this.advisorFactory.getAdvisors(factory));
			}
		}
		// <5> 返回所有 AspectJ 的所有的 Advisor 对象们
		return advisors;
	}

	/**
	 * Return whether the aspect bean with the given name is eligible.
	 * @param beanName the name of the aspect bean
	 * @return whether the bean is eligible
	 */
	protected boolean isEligibleBean(String beanName) {
		return true;
	}

}
