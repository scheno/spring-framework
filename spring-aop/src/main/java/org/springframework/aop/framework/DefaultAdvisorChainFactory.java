/*
 * Copyright 2002-2018 the original author or authors.
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
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.aopalliance.intercept.Interceptor;
import org.aopalliance.intercept.MethodInterceptor;

import org.springframework.aop.Advisor;
import org.springframework.aop.IntroductionAdvisor;
import org.springframework.aop.IntroductionAwareMethodMatcher;
import org.springframework.aop.MethodMatcher;
import org.springframework.aop.PointcutAdvisor;
import org.springframework.aop.framework.adapter.AdvisorAdapterRegistry;
import org.springframework.aop.framework.adapter.GlobalAdvisorAdapterRegistry;
import org.springframework.lang.Nullable;

/**
 * A simple but definitive way of working out an advice chain for a Method,
 * given an {@link Advised} object. Always rebuilds each advice chain;
 * caching can be provided by subclasses.
 *
 * @author Juergen Hoeller
 * @author Rod Johnson
 * @author Adrian Colyer
 * @since 2.0.3
 */
@SuppressWarnings("serial")
public class DefaultAdvisorChainFactory implements AdvisorChainFactory, Serializable {

	/**
	 * 从提供的配置实例 config 中获取 advisor 列表,遍历处理这些 advisor.如果是 IntroductionAdvisor,
	 * 则判断此 Advisor 能否应用到目标类 targetClass 上.如果是 PointcutAdvisor,则判断
	 * 此 Advisor 能否应用到目标方法 Method 上.将满足条件的 Advisor 通过 AdvisorAdaptor 转化成 Interceptor 列表返回.
	 */
	@Override
	public List<Object> getInterceptorsAndDynamicInterceptionAdvice(
			Advised config, Method method, @Nullable Class<?> targetClass) {

		// This is somewhat tricky... We have to process introductions first,
		// but we need to preserve order in the ultimate list.
		// <1> 获取 DefaultAdvisorAdapterRegistry 实例对象
		AdvisorAdapterRegistry registry = GlobalAdvisorAdapterRegistry.getInstance();
		// <2> 获取能够应用到 `targetClass` 的 Advisor 们
		Advisor[] advisors = config.getAdvisors();
		List<Object> interceptorList = new ArrayList<>(advisors.length);
		Class<?> actualClass = (targetClass != null ? targetClass : method.getDeclaringClass());
		// 查看是否包含 IntroductionAdvisor
		Boolean hasIntroductions = null;

		// <3> 遍历上一步获取到的 Advisor 们
		// 筛选出哪些 Advisor 需要处理当前被拦截的 `method`，并获取对应的 MethodInterceptor（Advice，如果不是方法拦截器则会包装成对应的 MethodInterceptor）
		for (Advisor advisor : advisors) {
			/*
			 * <3.1> 如果是 PointcutAdvisor 类型，则需要对目标对象的类型和被拦截的方法进行匹配
			 */
			if (advisor instanceof PointcutAdvisor) {
				// Add it conditionally.
				PointcutAdvisor pointcutAdvisor = (PointcutAdvisor) advisor;
				/*
				 * <3.1.1> 判断这个 PointcutAdvisor 是否匹配目标对象的类型，无法匹配则跳过
				 */
				if (config.isPreFiltered()  // AdvisedSupport 是否已经过滤过目标对象的类型
						|| pointcutAdvisor.getPointcut().getClassFilter().matches(actualClass)) { // 调用 Pointcut 的 ClassFilter 对目标对象的类型进行匹配
					// <3.1.2> 获取 Pointcut 的 MethodMatcher 方法匹配器对该方法进行匹配
					// 参考 AspectJExpressionPointcut，底层借助于 AspectJ 的处理
					MethodMatcher mm = pointcutAdvisor.getPointcut().getMethodMatcher();
					boolean match;
					if (mm instanceof IntroductionAwareMethodMatcher) {
						if (hasIntroductions == null) {
							// 查看是否包含 IntroductionAdvisor
							hasIntroductions = hasMatchingIntroductions(advisors, actualClass);
						}
						match = ((IntroductionAwareMethodMatcher) mm).matches(method, actualClass, hasIntroductions);
					}
					else {
						match = mm.matches(method, actualClass);
					}
					/*
					 * <3.1.3> 如果这个方法匹配成功，则进行下面的处理
					 */
					if (match) {
						// <3.1.4> 从 Advisor 中获取 Advice，并包装成 MethodInterceptor 拦截器对象（如果不是的话）
						MethodInterceptor[] interceptors = registry.getInterceptors(advisor);
						// <3.1.5> 若 MethodMatcher 的 `isRuntime()` 返回 `true`，则表明 MethodMatcher 要在运行时做一些检测
						if (mm.isRuntime()) {
							// Creating a new object instance in the getInterceptors() method
							// isn't a problem as we normally cache created chains.
							for (MethodInterceptor interceptor : interceptors) {
								// <3.1.5.1> 将上面获取到的 MethodInterceptor 和 MethodMatcher 包装成一个对象，并添加至 `interceptorList`
								interceptorList.add(new InterceptorAndDynamicMethodMatcher(interceptor, mm));
							}
						}
						// <3.1.6> 否则，直接将 MethodInterceptor 们添加至 `interceptorList`
						else {
							interceptorList.addAll(Arrays.asList(interceptors));
						}
					}
				}
			}
			/*
			 * <3.2> 否则，如果是 IntroductionAdvisor 类型，则需要对目标对象的类型进行匹配
			 */
			else if (advisor instanceof IntroductionAdvisor) {
				IntroductionAdvisor ia = (IntroductionAdvisor) advisor;
				/*
				 * <3.2.1> 判断这个 IntroductionAdvisor 是否匹配目标对象的类型，无法匹配则跳过
				 */
				if (config.isPreFiltered() // AdvisedSupport 是否已经过滤过目标对象的类型
						|| ia.getClassFilter().matches(actualClass)) { // 调用 Pointcut 的 ClassFilter 对目标对象的类型进行匹配
					// <3.2.2> 从 Advisor 中获取 Advice，并包装成 MethodInterceptor 拦截器对象（如果不是的话）
					Interceptor[] interceptors = registry.getInterceptors(advisor);
					// <3.2.3> 直接将 MethodInterceptor 们添加至 `interceptorList`
					interceptorList.addAll(Arrays.asList(interceptors));
				}
			}
			/*
			 * <3.3> 否则，不需要对目标对象的类型和被拦截的方法进行匹配
			 */
			else {
				// <3.3.1> 从 Advisor 中获取 Advice，并包装成 MethodInterceptor 拦截器对象（如果不是的话）
				Interceptor[] interceptors = registry.getInterceptors(advisor);
				// <3.3.2> 直接将 MethodInterceptor 们添加至 `interceptorList`
				interceptorList.addAll(Arrays.asList(interceptors));
			}
		}

		// <4> 返回 `interceptorList` 所有的 MethodInterceptor 拦截器
		// 因为 Advisor 是排好序的，所以这里的 `interceptorList` 是有序的
		return interceptorList;
	}

	/**
	 * Determine whether the Advisors contain matching introductions.
	 */
	private static boolean hasMatchingIntroductions(Advisor[] advisors, Class<?> actualClass) {
		for (Advisor advisor : advisors) {
			if (advisor instanceof IntroductionAdvisor) {
				IntroductionAdvisor ia = (IntroductionAdvisor) advisor;
				if (ia.getClassFilter().matches(actualClass)) {
					return true;
				}
			}
		}
		return false;
	}

}
