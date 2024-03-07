/*
 * Copyright 2002-2019 the original author or authors.
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

import java.lang.reflect.Constructor;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.cglib.proxy.Callback;
import org.springframework.cglib.proxy.Enhancer;
import org.springframework.cglib.proxy.Factory;
import org.springframework.objenesis.SpringObjenesis;
import org.springframework.util.ReflectionUtils;

/**
 * Objenesis-based extension of {@link CglibAopProxy} to create proxy instances
 * without invoking the constructor of the class. Used by default as of Spring 4.
 *
 * @author Oliver Gierke
 * @author Juergen Hoeller
 * @since 4.0
 */
@SuppressWarnings("serial")
class ObjenesisCglibAopProxy extends CglibAopProxy {

	private static final Log logger = LogFactory.getLog(ObjenesisCglibAopProxy.class);

	/**
	 * 创建一个 Objenesis 对象
	 * [**Objenesis**](http://objenesis.org/) 是一个小型 Java 库，目的是为一些特殊的 Class 对象实例化一个对象
	 * 应用场景：
	 * 1. 序列化，远程调用和持久化 - 对象需要实例化并存储为到一个特殊的状态，而没有调用代码
	 * 2. 代理，AOP 库和 Mock 对象 - 类可以被子类继承而子类不用担心父类的构造器
	 * 3. 容器框架 - 对象可以以非标准的方式被动态实例化
	 */
	private static final SpringObjenesis objenesis = new SpringObjenesis();


	/**
	 * Create a new ObjenesisCglibAopProxy for the given AOP configuration.
	 * @param config the AOP configuration as AdvisedSupport object
	 */
	public ObjenesisCglibAopProxy(AdvisedSupport config) {
		super(config);
	}


	@Override
	protected Object createProxyClassAndInstance(Enhancer enhancer, Callback[] callbacks) {
		// <1> 先创建代理对象的 Class 对象（目标类的子类）
		Class<?> proxyClass = enhancer.createClass();
		Object proxyInstance = null;

		// <2> 是否使用 Objenesis 来实例化代理对象，默认会
		// 可通过 在 `spring.properties` 文件中添加 `spring.objenesis.ignore=false` 来禁止
		if (objenesis.isWorthTrying()) {
			try {
				// <2.1> 通过 Objenesis 实例化代理对象（非标准方式，不使用构造方法进行实例化）
				proxyInstance = objenesis.newInstance(proxyClass, enhancer.getUseCache());
			}
			catch (Throwable ex) {
				logger.debug("Unable to instantiate proxy using Objenesis, " +
						"falling back to regular proxy construction", ex);
			}
		}

		// <3> 如果借助 Objenesis 实例化代理对象失败
		if (proxyInstance == null) {
			// Regular instantiation via default constructor...
			try {
				// <3.1> 选择构造器，指定了参数则使用对应的构造器，否则使用默认构造器
				Constructor<?> ctor = (this.constructorArgs != null ?
						proxyClass.getDeclaredConstructor(this.constructorArgTypes) :
						proxyClass.getDeclaredConstructor());
				ReflectionUtils.makeAccessible(ctor);
				// <3.2> 通过构造器实例化代理对象（反射）
				proxyInstance = (this.constructorArgs != null ?
						ctor.newInstance(this.constructorArgs) : ctor.newInstance());
			}
			catch (Throwable ex) {
				throw new AopConfigException("Unable to instantiate proxy using Objenesis, " +
						"and regular proxy instantiation via default constructor fails as well", ex);
			}
		}

		// <4> 设置 Callback 数组
		((Factory) proxyInstance).setCallbacks(callbacks);
		// <5> 返回代理对象
		return proxyInstance;
	}

}
