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

package org.springframework.aop.framework.autoproxy;

import java.util.List;

import org.springframework.aop.Advisor;
import org.springframework.aop.TargetSource;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;

/**
 * Generic auto proxy creator that builds AOP proxies for specific beans
 * based on detected Advisors for each bean.
 *
 * <p>Subclasses may override the {@link #findCandidateAdvisors()} method to
 * return a custom list of Advisors applying to any object. Subclasses can
 * also override the inherited {@link #shouldSkip} method to exclude certain
 * objects from auto-proxying.
 *
 * <p>Advisors or advices requiring ordering should be annotated with
 * {@link org.springframework.core.annotation.Order @Order} or implement the
 * {@link org.springframework.core.Ordered} interface. This class sorts
 * advisors using the {@link AnnotationAwareOrderComparator}. Advisors that are
 * not annotated with {@code @Order} or don't implement the {@code Ordered}
 * interface will be considered as unordered; they will appear at the end of the
 * advisor chain in an undefined order.
 *
 * @author Rod Johnson
 * @author Juergen Hoeller
 * @see #findCandidateAdvisors
 */
@SuppressWarnings("serial")
public abstract class AbstractAdvisorAutoProxyCreator extends AbstractAutoProxyCreator {

	/** Advisor 检索工具类 */
	@Nullable
	private BeanFactoryAdvisorRetrievalHelper advisorRetrievalHelper;


	@Override
	public void setBeanFactory(BeanFactory beanFactory) {
		super.setBeanFactory(beanFactory);
		if (!(beanFactory instanceof ConfigurableListableBeanFactory)) {
			throw new IllegalArgumentException(
					"AdvisorAutoProxyCreator requires a ConfigurableListableBeanFactory: " + beanFactory);
		}
		// 初始化工作
		initBeanFactory((ConfigurableListableBeanFactory) beanFactory);
	}

	protected void initBeanFactory(ConfigurableListableBeanFactory beanFactory) {
		// 设置 Advisor 检索工具类为 BeanFactoryAdvisorRetrievalHelperAdapter
		this.advisorRetrievalHelper = new BeanFactoryAdvisorRetrievalHelperAdapter(beanFactory);
	}


	@Override
	@Nullable
	protected Object[] getAdvicesAndAdvisorsForBean(
			Class<?> beanClass, String beanName, @Nullable TargetSource targetSource) {
		// 获取能够应用到当前 Bean 的所有 Advisor（已根据 @Order 排序）
		List<Advisor> advisors = findEligibleAdvisors(beanClass, beanName);
		if (advisors.isEmpty()) {
			return DO_NOT_PROXY;
		}
		// 转换成数组并返回
		return advisors.toArray();
	}

	/**
	 * Find all eligible Advisors for auto-proxying this class.
	 * @param beanClass the clazz to find advisors for
	 * @param beanName the name of the currently proxied bean
	 * @return the empty List, not {@code null},
	 * if there are no pointcuts or interceptors
	 * @see #findCandidateAdvisors
	 * @see #sortAdvisors
	 * @see #extendAdvisors
	 */
	protected List<Advisor> findEligibleAdvisors(Class<?> beanClass, String beanName) {
		/*
		 * <1> 解析出当前 IoC 容器所有的 Advisor 对象
		 * 1. 本身是 Advisor 类型的 Bean，默认情况下都会
		 * 2. 从带有 @AspectJ 注解的 Bean 中解析出来的 Advisor，子类 AnnotationAwareAspectJAutoProxyCreator 会扫描并解析
		 *    PointcutAdvisor：带有 @Before|@After|@Around|@AfterReturning|@AfterThrowing 注解的方法
		 *       其中 Pointcut 为 AspectJExpressionPointcut，Advice 就是注解标注的方法
		 *    IntroductionAdvisor：带有 @DeclareParents 注解的字段
		 *
		 * 未排序，获取到的 Advisor 在同一个 AspectJ 中的顺序是根据注解来的，@Around > @Before > @After > @AfterReturning > @AfterThrowing
		 */
		List<Advisor> candidateAdvisors = findCandidateAdvisors();
		/*
		 * <2> 筛选出能够应用到 `beanClass` 上面的所有 Advisor 对象并返回
		 * 也就是通过 ClassFilter 进行匹配，然后再通过 MethodMatcher 对所有方法进行匹配（有一个即可）
		 * AspectJExpressionPointcut 就实现了 ClassFilter 和 MethodMatcher
		 */
		List<Advisor> eligibleAdvisors = findAdvisorsThatCanApply(candidateAdvisors, beanClass, beanName);
		/*
		 * <3> 抽象方法，交由子类拓展
		 * 例如 AspectJAwareAdvisorAutoProxyCreator 的实现
		 * 如果 `eligibleAdvisors` 中存在和 AspectJ 相关的 Advisor
		 * 则会在 `eligibleAdvisors` 首部添加一个 DefaultPointcutAdvisor 对象，对应的 Advice 为 ExposeInvocationInterceptor 对象
		 * 用于暴露 MethodInvocation 对象（Joinpoint 对象），存储在 ThreadLocal 中，在其他地方则可以使用
		 */
		extendAdvisors(eligibleAdvisors);
		// <4> 对 `eligibleAdvisors` 集合进行排序，根据 @Order 注解进行排序
		if (!eligibleAdvisors.isEmpty()) {
			// 不同的 AspectJ 根据 @Order 排序
			// 同一个 AspectJ 中不同 Advisor 的排序：AspectJAfterThrowingAdvice > AspectJAfterReturningAdvice > AspectJAfterAdvice > AspectJAroundAdvice > AspectJMethodBeforeAdvice
			eligibleAdvisors = sortAdvisors(eligibleAdvisors);
		}
		return eligibleAdvisors;
	}

	/**
	 * Find all candidate Advisors to use in auto-proxying.
	 * @return the List of candidate Advisors
	 */
	protected List<Advisor> findCandidateAdvisors() {
		Assert.state(this.advisorRetrievalHelper != null, "No BeanFactoryAdvisorRetrievalHelper available");
		// 借助 BeanFactoryAdvisorRetrievalHelperAdapter 从 IoC 容器中查找所有的 Advisor 对象
		return this.advisorRetrievalHelper.findAdvisorBeans();
	}

	/**
	 * Search the given candidate Advisors to find all Advisors that
	 * can apply to the specified bean.
	 * @param candidateAdvisors the candidate Advisors
	 * @param beanClass the target's bean class
	 * @param beanName the target's bean name
	 * @return the List of applicable Advisors
	 * @see ProxyCreationContext#getCurrentProxiedBeanName()
	 */
	protected List<Advisor> findAdvisorsThatCanApply(
			List<Advisor> candidateAdvisors, Class<?> beanClass, String beanName) {

		ProxyCreationContext.setCurrentProxiedBeanName(beanName);
		try {
			/*
			 * 筛选出能够应用到 `beanClass` 上面的所有 Advisor 对象并返回
			 * 也就是通过 ClassFilter 进行匹配，然后再通过 MethodMatcher 对所有方法进行匹配（有一个即可）
			 * AspectJExpressionPointcut 就实现了 ClassFilter 和 MethodMatcher
			 */
			return AopUtils.findAdvisorsThatCanApply(candidateAdvisors, beanClass);
		}
		finally {
			ProxyCreationContext.setCurrentProxiedBeanName(null);
		}
	}

	/**
	 * Return whether the Advisor bean with the given name is eligible
	 * for proxying in the first place.
	 * @param beanName the name of the Advisor bean
	 * @return whether the bean is eligible
	 */
	protected boolean isEligibleAdvisorBean(String beanName) {
		return true;
	}

	/**
	 * Sort advisors based on ordering. Subclasses may choose to override this
	 * method to customize the sorting strategy.
	 * @param advisors the source List of Advisors
	 * @return the sorted List of Advisors
	 * @see org.springframework.core.Ordered
	 * @see org.springframework.core.annotation.Order
	 * @see org.springframework.core.annotation.AnnotationAwareOrderComparator
	 */
	protected List<Advisor> sortAdvisors(List<Advisor> advisors) {
		AnnotationAwareOrderComparator.sort(advisors);
		return advisors;
	}

	/**
	 * Extension hook that subclasses can override to register additional Advisors,
	 * given the sorted Advisors obtained to date.
	 * <p>The default implementation is empty.
	 * <p>Typically used to add Advisors that expose contextual information
	 * required by some of the later advisors.
	 * @param candidateAdvisors the Advisors that have already been identified as
	 * applying to a given bean
	 */
	protected void extendAdvisors(List<Advisor> candidateAdvisors) {
	}

	/**
	 * This auto-proxy creator always returns pre-filtered Advisors.
	 */
	@Override
	protected boolean advisorsPreFiltered() {
		return true;
	}


	/**
	 * Subclass of BeanFactoryAdvisorRetrievalHelper that delegates to
	 * surrounding AbstractAdvisorAutoProxyCreator facilities.
	 */
	private class BeanFactoryAdvisorRetrievalHelperAdapter extends BeanFactoryAdvisorRetrievalHelper {

		public BeanFactoryAdvisorRetrievalHelperAdapter(ConfigurableListableBeanFactory beanFactory) {
			super(beanFactory);
		}

		@Override
		protected boolean isEligibleBean(String beanName) {
			return AbstractAdvisorAutoProxyCreator.this.isEligibleAdvisorBean(beanName);
		}
	}

}
