/*
 * Copyright 2002-2012 the original author or authors.
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

package org.springframework.aop.support;

import org.springframework.aop.ClassFilter;
import org.springframework.aop.MethodMatcher;
import org.springframework.aop.Pointcut;

/**
 * Convenient superclass when we want to force subclasses to implement the
 * {@link MethodMatcher} interface but subclasses will want to be pointcuts.
 *
 * <p>The {@link #setClassFilter "classFilter"} property can be set to customize
 * {@link ClassFilter} behavior. The default is {@link ClassFilter#TRUE}.
 *
 * @author Rod Johnson
 * @author Juergen Hoeller
 */
public abstract class StaticMethodMatcherPointcut extends StaticMethodMatcher implements Pointcut {

	// 默认情况类过滤器总是匹配通过
	private ClassFilter classFilter = ClassFilter.TRUE;


	/**
	 * Set the {@link ClassFilter} to use for this pointcut.
	 * Default is {@link ClassFilter#TRUE}.
	 */
	// 设置类过滤器
	public void setClassFilter(ClassFilter classFilter) {
		this.classFilter = classFilter;
	}

	// 获取类过滤器
	@Override
	public ClassFilter getClassFilter() {
		return this.classFilter;
	}

	// 返回方法匹配器，就是当前对象，因为当前对象继承的 StaticMethodMatcher 就是一个 MethodMatcher
	@Override
	public final MethodMatcher getMethodMatcher() {
		return this;
	}

}
