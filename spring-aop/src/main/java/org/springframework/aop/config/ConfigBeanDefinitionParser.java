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

package org.springframework.aop.config;

import java.util.ArrayList;
import java.util.List;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import org.springframework.aop.aspectj.AspectJAfterAdvice;
import org.springframework.aop.aspectj.AspectJAfterReturningAdvice;
import org.springframework.aop.aspectj.AspectJAfterThrowingAdvice;
import org.springframework.aop.aspectj.AspectJAroundAdvice;
import org.springframework.aop.aspectj.AspectJExpressionPointcut;
import org.springframework.aop.aspectj.AspectJMethodBeforeAdvice;
import org.springframework.aop.aspectj.AspectJPointcutAdvisor;
import org.springframework.aop.aspectj.DeclareParentsAdvisor;
import org.springframework.aop.support.DefaultBeanFactoryPointcutAdvisor;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanReference;
import org.springframework.beans.factory.config.ConstructorArgumentValues;
import org.springframework.beans.factory.config.RuntimeBeanNameReference;
import org.springframework.beans.factory.config.RuntimeBeanReference;
import org.springframework.beans.factory.parsing.CompositeComponentDefinition;
import org.springframework.beans.factory.parsing.ParseState;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.beans.factory.xml.BeanDefinitionParser;
import org.springframework.beans.factory.xml.ParserContext;
import org.springframework.lang.Nullable;
import org.springframework.util.StringUtils;
import org.springframework.util.xml.DomUtils;

/**
 * {@link BeanDefinitionParser} for the {@code <aop:config>} tag.
 *
 * @author Rob Harrop
 * @author Juergen Hoeller
 * @author Adrian Colyer
 * @author Mark Fisher
 * @author Ramnivas Laddad
 * @since 2.0
 */
class ConfigBeanDefinitionParser implements BeanDefinitionParser {

	private static final String ASPECT = "aspect";
	private static final String EXPRESSION = "expression";
	private static final String ID = "id";
	private static final String POINTCUT = "pointcut";
	private static final String ADVICE_BEAN_NAME = "adviceBeanName";
	private static final String ADVISOR = "advisor";
	private static final String ADVICE_REF = "advice-ref";
	private static final String POINTCUT_REF = "pointcut-ref";
	private static final String REF = "ref";
	private static final String BEFORE = "before";
	private static final String DECLARE_PARENTS = "declare-parents";
	private static final String TYPE_PATTERN = "types-matching";
	private static final String DEFAULT_IMPL = "default-impl";
	private static final String DELEGATE_REF = "delegate-ref";
	private static final String IMPLEMENT_INTERFACE = "implement-interface";
	private static final String AFTER = "after";
	private static final String AFTER_RETURNING_ELEMENT = "after-returning";
	private static final String AFTER_THROWING_ELEMENT = "after-throwing";
	private static final String AROUND = "around";
	private static final String RETURNING = "returning";
	private static final String RETURNING_PROPERTY = "returningName";
	private static final String THROWING = "throwing";
	private static final String THROWING_PROPERTY = "throwingName";
	private static final String ARG_NAMES = "arg-names";
	private static final String ARG_NAMES_PROPERTY = "argumentNames";
	private static final String ASPECT_NAME_PROPERTY = "aspectName";
	private static final String DECLARATION_ORDER_PROPERTY = "declarationOrder";
	private static final String ORDER_PROPERTY = "order";
	private static final int METHOD_INDEX = 0;
	private static final int POINTCUT_INDEX = 1;
	private static final int ASPECT_INSTANCE_FACTORY_INDEX = 2;

	private ParseState parseState = new ParseState();


	@Override
	@Nullable
	public BeanDefinition parse(Element element, ParserContext parserContext) {
		CompositeComponentDefinition compositeDef =
				new CompositeComponentDefinition(element.getTagName(), parserContext.extractSource(element));
		parserContext.pushContainingComponent(compositeDef);

		// <1> 解析 <aop:config /> 标签
		// 注册 AspectJAwareAdvisorAutoProxyCreator 自动代理对象（如果需要的话），设置为优先级最高
		// 过程和 @EnableAspectJAutoProxy、<aop:aspectj-autoproxy /> 差不多
		configureAutoProxyCreator(parserContext, element);

		// <2> 获取 <aop:config /> 的子标签，遍历进行处理
		List<Element> childElts = DomUtils.getChildElements(element);
		for (Element elt: childElts) {
			// 获取子标签的名称
			String localName = parserContext.getDelegate().getLocalName(elt);
			if (POINTCUT.equals(localName)) {
				// <2.1> 处理 <aop:pointcut /> 子标签，解析出 AspectJExpressionPointcut 对象并注册
				parsePointcut(elt, parserContext);
			}
			else if (ADVISOR.equals(localName)) {
				// <2.2> 处理 <aop:advisor /> 子标签，解析出 DefaultBeanFactoryPointcutAdvisor 对象并注册，了指定 Advice 和 Pointcut（如果有）
				parseAdvisor(elt, parserContext);
			}
			else if (ASPECT.equals(localName)) {
				// <2.3> 处理 <aop:aspectj /> 子标签，解析出所有的 AspectJPointcutAdvisor 对象并注册，里面包含了 Advice 对象和对应的 Pointcut 对象
				// 同时存在 Pointcut 配置，也会解析出 AspectJExpressionPointcut 对象并注册
				parseAspect(elt, parserContext);
			}
		}

		// <3> 将 `parserContext` 上下文中已注册的 BeanDefinition 合并到上面 `compositeDef` 中（暂时忽略）
		parserContext.popAndRegisterContainingComponent();
		return null;
	}

	/**
	 * Configures the auto proxy creator needed to support the {@link BeanDefinition BeanDefinitions}
	 * created by the '{@code <aop:config/>}' tag. Will force class proxying if the
	 * '{@code proxy-target-class}' attribute is set to '{@code true}'.
	 * @see AopNamespaceUtils
	 */
	private void configureAutoProxyCreator(ParserContext parserContext, Element element) {
		// 注册 AspectJAwareAdvisorAutoProxyCreator 自动代理对象（如果需要的话）
		AopNamespaceUtils.registerAspectJAutoProxyCreatorIfNecessary(parserContext, element);
	}

	/**
	 * Parses the supplied {@code <advisor>} element and registers the resulting
	 * {@link org.springframework.aop.Advisor} and any resulting {@link org.springframework.aop.Pointcut}
	 * with the supplied {@link BeanDefinitionRegistry}.
	 */
	private void parseAdvisor(Element advisorElement, ParserContext parserContext) {
		// <1> 解析 <aop:advisor /> 标签
		// 创建一个 DefaultBeanFactoryPointcutAdvisor 类型的 RootBeanDefinition 对象，并指定了 Advice
		AbstractBeanDefinition advisorDef = createAdvisorBeanDefinition(advisorElement, parserContext);
		// <2> 获取 `id` 属性
		String id = advisorElement.getAttribute(ID);

		try {
			this.parseState.push(new AdvisorEntry(id));
			String advisorBeanName = id;
			// <3> 注册第 `1` 步创建的 RootBeanDefinition
			if (StringUtils.hasText(advisorBeanName)) {
				// <3.1> 如果 `id` 不为空，则取其作为名称
				parserContext.getRegistry().registerBeanDefinition(advisorBeanName, advisorDef);
			}
			else {
				// <3.2> 否则，生成一个名称，也就是 `className`
				advisorBeanName = parserContext.getReaderContext().registerWithGeneratedName(advisorDef);
			}

			// <4> 获取这个 Advisor 对应的 Pointcut（也许就是一个 AspectJExpressionPointcut，也可能是引用的 Pointcut 的名称）
			Object pointcut = parsePointcutProperty(advisorElement, parserContext);
			// <4.1> 如果是 AspectJExpressionPointcut
			if (pointcut instanceof BeanDefinition) {
				// 第 `1` 步创建的 RootBeanDefinition 添加 `pointcut` 属性，指向这个 AspectJExpressionPointcut
				advisorDef.getPropertyValues().add(POINTCUT, pointcut);
				parserContext.registerComponent(
						new AdvisorComponentDefinition(advisorBeanName, advisorDef, (BeanDefinition) pointcut));
			}
			// <4.2> 否则，如果是一个引用的 Pointcut 的名称
			else if (pointcut instanceof String) {
				// 第 `1` 步创建的 RootBeanDefinition 添加 `pointcut` 属性，指向这个名称对应的引用
				advisorDef.getPropertyValues().add(POINTCUT, new RuntimeBeanReference((String) pointcut));
				parserContext.registerComponent(
						new AdvisorComponentDefinition(advisorBeanName, advisorDef));
			}
		}
		finally {
			this.parseState.pop();
		}
	}

	/**
	 * Create a {@link RootBeanDefinition} for the advisor described in the supplied. Does <strong>not</strong>
	 * parse any associated '{@code pointcut}' or '{@code pointcut-ref}' attributes.
	 */
	private AbstractBeanDefinition createAdvisorBeanDefinition(Element advisorElement, ParserContext parserContext) {
		// <1> 创建一个 DefaultBeanFactoryPointcutAdvisor 类型的 RootBeanDefinition 对象
		RootBeanDefinition advisorDefinition = new RootBeanDefinition(DefaultBeanFactoryPointcutAdvisor.class);
		// <2> 设置来源
		advisorDefinition.setSource(parserContext.extractSource(advisorElement));

		// <3> 获取 `advice-ref` 属性配置，必须配置一个对应的 Advice
		String adviceRef = advisorElement.getAttribute(ADVICE_REF);
		if (!StringUtils.hasText(adviceRef)) {
			parserContext.getReaderContext().error(
					"'advice-ref' attribute contains empty value.", advisorElement, this.parseState.snapshot());
		}
		else {
			// <4> 将 `advice-ref` 添加至 `adviceBeanName` 属性，也就是指向这个 Advice 引用
			advisorDefinition.getPropertyValues().add(
					ADVICE_BEAN_NAME, new RuntimeBeanNameReference(adviceRef));
		}

		// <5> 根据 `order` 配置为 RootBeanDefinition 设置优先级
		if (advisorElement.hasAttribute(ORDER_PROPERTY)) {
			advisorDefinition.getPropertyValues().add(
					ORDER_PROPERTY, advisorElement.getAttribute(ORDER_PROPERTY));
		}

		// <6> 返回刚创建的 RootBeanDefinition
		return advisorDefinition;
	}

	private void parseAspect(Element aspectElement, ParserContext parserContext) {
		// <1> 获取 `id` 和 `ref` 属性
		String aspectId = aspectElement.getAttribute(ID);
		String aspectName = aspectElement.getAttribute(REF);

		try {
			this.parseState.push(new AspectEntry(aspectId, aspectName));
			// <2> 定义两个集合 `beanDefinitions`、`beanReferences`
			// 解析出来的 BeanDefinition
			List<BeanDefinition> beanDefinitions = new ArrayList<>();
			// 需要引用的 Bean
			List<BeanReference> beanReferences = new ArrayList<>();

			// <3> 获取所有的 <aop:declare-parents /> 子标签，遍历进行处理
			List<Element> declareParents = DomUtils.getChildElementsByTagName(aspectElement, DECLARE_PARENTS);
			for (int i = METHOD_INDEX; i < declareParents.size(); i++) {
				Element declareParentsElement = declareParents.get(i);
				// <3.1> 解析 <aop:declare-parents /> 子标签
				// 解析出 DeclareParentsAdvisor 对象，添加至 `beanDefinitions`
				beanDefinitions.add(parseDeclareParents(declareParentsElement, parserContext));
			}

			// We have to parse "advice" and all the advice kinds in one loop, to get the
			// ordering semantics right.
			// <4> 获取 <aop:aspectj /> 所有的子节点，遍历进行处理
			NodeList nodeList = aspectElement.getChildNodes();
			boolean adviceFoundAlready = false;
			for (int i = 0; i < nodeList.getLength(); i++) {
				Node node = nodeList.item(i);
				// <4.1> 如果是 <aop:around />、<aop:before />、<aop:after />、<aop:after-returning />、<aop:after-throwing /> 标签，则进行处理
				if (isAdviceNode(node, parserContext)) {
					// <4.2> 如果第一次进来，那么就是配置了 Advice，则 `ref` 必须指定一个 Bean，因为这些 Advice 的 `method` 需要从这个 Bean 中获取
					if (!adviceFoundAlready) {
						adviceFoundAlready = true;
						if (!StringUtils.hasText(aspectName)) {
							parserContext.getReaderContext().error(
									"<aspect> tag needs aspect bean reference via 'ref' attribute when declaring advices.",
									aspectElement, this.parseState.snapshot());
							return;
						}
						// <4.2.1> 往 `beanReferences` 添加需要引用的 Bean
						beanReferences.add(new RuntimeBeanReference(aspectName));
					}
					// <4.3> 根据 Advice 标签进行解析
					// 创建一个 AspectJPointcutAdvisor 对象，里面包含了 Advice 对象和对应的 Pointcut 对象，并进行注册
					AbstractBeanDefinition advisorDefinition = parseAdvice(
							aspectName, i, aspectElement, (Element) node, parserContext, beanDefinitions, beanReferences);
					beanDefinitions.add(advisorDefinition);
				}
			}

			// <5> 将上面创建的所有 Advisor 和引用对象都封装到 AspectComponentDefinition 对象中
			// 并放入 `parserContext` 上下文中，暂时忽略
			AspectComponentDefinition aspectComponentDefinition = createAspectComponentDefinition(
					aspectElement, aspectId, beanDefinitions, beanReferences, parserContext);
			parserContext.pushContainingComponent(aspectComponentDefinition);

			// <6> 获取所有的 <aop:pointcut /> 子标签，进行遍历处理
			List<Element> pointcuts = DomUtils.getChildElementsByTagName(aspectElement, POINTCUT);
			for (Element pointcutElement : pointcuts) {
				// <6.1> 解析出 AspectJExpressionPointcut 对象并注册
				parsePointcut(pointcutElement, parserContext);
			}

			parserContext.popAndRegisterContainingComponent();
		}
		finally {
			this.parseState.pop();
		}
	}

	private AspectComponentDefinition createAspectComponentDefinition(
			Element aspectElement, String aspectId, List<BeanDefinition> beanDefs,
			List<BeanReference> beanRefs, ParserContext parserContext) {

		BeanDefinition[] beanDefArray = beanDefs.toArray(new BeanDefinition[0]);
		BeanReference[] beanRefArray = beanRefs.toArray(new BeanReference[0]);
		Object source = parserContext.extractSource(aspectElement);
		return new AspectComponentDefinition(aspectId, beanDefArray, beanRefArray, source);
	}

	/**
	 * Return {@code true} if the supplied node describes an advice type. May be one of:
	 * '{@code before}', '{@code after}', '{@code after-returning}',
	 * '{@code after-throwing}' or '{@code around}'.
	 */
	private boolean isAdviceNode(Node aNode, ParserContext parserContext) {
		if (!(aNode instanceof Element)) {
			return false;
		}
		else {
			String name = parserContext.getDelegate().getLocalName(aNode);
			return (BEFORE.equals(name) || AFTER.equals(name) || AFTER_RETURNING_ELEMENT.equals(name) ||
					AFTER_THROWING_ELEMENT.equals(name) || AROUND.equals(name));
		}
	}

	/**
	 * Parse a '{@code declare-parents}' element and register the appropriate
	 * DeclareParentsAdvisor with the BeanDefinitionRegistry encapsulated in the
	 * supplied ParserContext.
	 */
	private AbstractBeanDefinition parseDeclareParents(Element declareParentsElement, ParserContext parserContext) {
		BeanDefinitionBuilder builder = BeanDefinitionBuilder.rootBeanDefinition(DeclareParentsAdvisor.class);
		builder.addConstructorArgValue(declareParentsElement.getAttribute(IMPLEMENT_INTERFACE));
		builder.addConstructorArgValue(declareParentsElement.getAttribute(TYPE_PATTERN));

		String defaultImpl = declareParentsElement.getAttribute(DEFAULT_IMPL);
		String delegateRef = declareParentsElement.getAttribute(DELEGATE_REF);

		if (StringUtils.hasText(defaultImpl) && !StringUtils.hasText(delegateRef)) {
			builder.addConstructorArgValue(defaultImpl);
		}
		else if (StringUtils.hasText(delegateRef) && !StringUtils.hasText(defaultImpl)) {
			builder.addConstructorArgReference(delegateRef);
		}
		else {
			parserContext.getReaderContext().error(
					"Exactly one of the " + DEFAULT_IMPL + " or " + DELEGATE_REF + " attributes must be specified",
					declareParentsElement, this.parseState.snapshot());
		}

		AbstractBeanDefinition definition = builder.getBeanDefinition();
		definition.setSource(parserContext.extractSource(declareParentsElement));
		parserContext.getReaderContext().registerWithGeneratedName(definition);
		return definition;
	}

	/**
	 * Parses one of '{@code before}', '{@code after}', '{@code after-returning}',
	 * '{@code after-throwing}' or '{@code around}' and registers the resulting
	 * BeanDefinition with the supplied BeanDefinitionRegistry.
	 * @return the generated advice RootBeanDefinition
	 */
	private AbstractBeanDefinition parseAdvice(
			String aspectName, int order, Element aspectElement, Element adviceElement, ParserContext parserContext,
			List<BeanDefinition> beanDefinitions, List<BeanReference> beanReferences) {

		try {
			this.parseState.push(new AdviceEntry(parserContext.getDelegate().getLocalName(adviceElement)));

			// create the method factory bean
			// <1> 创建 MethodLocatingFactoryBean 类型的 RootBeanDefinition
			// 因为通过标签配置的 Advice 对应的方法在其他 Bean 中，那么可以借助于 FactoryBean 来进行创建
			RootBeanDefinition methodDefinition = new RootBeanDefinition(MethodLocatingFactoryBean.class);
			// <1.1> 获取 `targetBeanName` 和 `method` 并进行设置
			methodDefinition.getPropertyValues().add("targetBeanName", aspectName);
			methodDefinition.getPropertyValues().add("methodName", adviceElement.getAttribute("method"));
			// <1.2> 设置这个 Bean 是由 Spring 内部合成的
			methodDefinition.setSynthetic(true);

			// create instance factory definition
			// <2> 创建一个 SimpleBeanFactoryAwareAspectInstanceFactory 类型的 RootBeanDefinition
			RootBeanDefinition aspectFactoryDef =
					new RootBeanDefinition(SimpleBeanFactoryAwareAspectInstanceFactory.class);
			// <2.1> 设置了 AspectJ 对应的 名称，用于获取这个 AspectJ 的实例对象
			aspectFactoryDef.getPropertyValues().add("aspectBeanName", aspectName);
			// <2.2> 设置这个 Bean 是由 Spring 内部合成的
			aspectFactoryDef.setSynthetic(true);

			// register the pointcut
			// <3> 创建一个 Advice 对象，包含了对应的 Pointcut
			AbstractBeanDefinition adviceDef = createAdviceDefinition(
					adviceElement, parserContext, aspectName, order, methodDefinition, aspectFactoryDef,
					beanDefinitions, beanReferences);

			// configure the advisor
			// <4> 创建一个 AspectJPointcutAdvisor 类型的 RootBeanDefinition 对象，用于包装上面创建的 Advice
			// Spring AOP 中的 Advice 都是放入 Advisor “容器” 中
			RootBeanDefinition advisorDefinition = new RootBeanDefinition(AspectJPointcutAdvisor.class);
			// <4.1> 设置来源
			advisorDefinition.setSource(parserContext.extractSource(adviceElement));
			// <4.2> 将上面创建的 Advice 对象作为构造器入参
			advisorDefinition.getConstructorArgumentValues().addGenericArgumentValue(adviceDef);
			// <4.3> 设置 `order` 优先级
			if (aspectElement.hasAttribute(ORDER_PROPERTY)) {
				advisorDefinition.getPropertyValues().add(
						ORDER_PROPERTY, aspectElement.getAttribute(ORDER_PROPERTY));
			}

			// register the final advisor
			// <5> 注册这个 AspectJPointcutAdvisor，自动生成名字
			parserContext.getReaderContext().registerWithGeneratedName(advisorDefinition);

			// <6> 返回这个已注册的 AspectJPointcutAdvisor
			return advisorDefinition;
		}
		finally {
			this.parseState.pop();
		}
	}

	/**
	 * Creates the RootBeanDefinition for a POJO advice bean. Also causes pointcut
	 * parsing to occur so that the pointcut may be associate with the advice bean.
	 * This same pointcut is also configured as the pointcut for the enclosing
	 * Advisor definition using the supplied MutablePropertyValues.
	 */
	private AbstractBeanDefinition createAdviceDefinition(
			Element adviceElement, ParserContext parserContext, String aspectName, int order,
			RootBeanDefinition methodDef, RootBeanDefinition aspectFactoryDef,
			List<BeanDefinition> beanDefinitions, List<BeanReference> beanReferences) {

		// <1> 根据 Advice 标签创建对应的 Advice
		// <aop:before /> -> AspectJMethodBeforeAdvice
		// <aop:after /> -> AspectJAfterAdvice
		// <aop:after-returning /> -> AspectJAfterReturningAdvice
		// <aop:after-throwing /> -> AspectJAfterThrowingAdvice
		// <aop:around /> -> AspectJAroundAdvice
		RootBeanDefinition adviceDefinition = new RootBeanDefinition(getAdviceClass(adviceElement, parserContext));
		// <1.1> 设置来源
		adviceDefinition.setSource(parserContext.extractSource(adviceElement));

		// <1.2> 设置引用的 AspectJ 的名称
		adviceDefinition.getPropertyValues().add(ASPECT_NAME_PROPERTY, aspectName);
		// <1.3> 设置优先级
		adviceDefinition.getPropertyValues().add(DECLARATION_ORDER_PROPERTY, order);

		if (adviceElement.hasAttribute(RETURNING)) {
			adviceDefinition.getPropertyValues().add(
					RETURNING_PROPERTY, adviceElement.getAttribute(RETURNING));
		}
		if (adviceElement.hasAttribute(THROWING)) {
			adviceDefinition.getPropertyValues().add(
					THROWING_PROPERTY, adviceElement.getAttribute(THROWING));
		}
		if (adviceElement.hasAttribute(ARG_NAMES)) {
			adviceDefinition.getPropertyValues().add(
					ARG_NAMES_PROPERTY, adviceElement.getAttribute(ARG_NAMES));
		}

		// <2> 获取 Advice 的构造器参数对象 `cav`
		// 设置 1. 引用的方法、2. Pointcut（也许是引用的 Pointcut 的名称）、3. 引用的方法所属 AspectJ 对象
		// 你点进这些 Advice 类型的对象中看看构造方法就知道怎么回事，例如：AspectJMethodBeforeAdvice
		ConstructorArgumentValues cav = adviceDefinition.getConstructorArgumentValues();
		// <2.1> 往 `cav` 添加 Advice 对应的方法作为入参
		cav.addIndexedArgumentValue(METHOD_INDEX, methodDef);

		// <2.2> 解析出对应的 Pointcut 对象（可能是一个 AspectJExpressionPointcut，也可能是引用的 Pointcut 的一个运行时引用对象）
		Object pointcut = parsePointcutProperty(adviceElement, parserContext);
		// <2.2.1> 如果是 AspectJExpressionPointcut
		if (pointcut instanceof BeanDefinition) {
			// 往 `cav` 添加 `pointcut` 入参
			cav.addIndexedArgumentValue(POINTCUT_INDEX, pointcut);
			// 添加至 `beanDefinitions`
			beanDefinitions.add((BeanDefinition) pointcut);
		}
		// <2.2.2> 否则，如果是 引用的 Pointcut
		else if (pointcut instanceof String) {
			// 根据引用的 Pointcut 的名称生成一个引用对象
			RuntimeBeanReference pointcutRef = new RuntimeBeanReference((String) pointcut);
			// 往构 `cav` 添加 `pointcut` 入参
			cav.addIndexedArgumentValue(POINTCUT_INDEX, pointcutRef);
			// 添加至 `pointcutRef`
			beanReferences.add(pointcutRef);
		}

		// <2.3> 往 `cav` 添加 Advice 对应的方法所在 Bean 作为入参
		cav.addIndexedArgumentValue(ASPECT_INSTANCE_FACTORY_INDEX, aspectFactoryDef);

		// <3> 返回为 Advice 创建的 RootBeanDefinition 对象
		return adviceDefinition;
	}

	/**
	 * Gets the advice implementation class corresponding to the supplied {@link Element}.
	 */
	private Class<?> getAdviceClass(Element adviceElement, ParserContext parserContext) {
		String elementName = parserContext.getDelegate().getLocalName(adviceElement);
		if (BEFORE.equals(elementName)) {
			return AspectJMethodBeforeAdvice.class;
		}
		else if (AFTER.equals(elementName)) {
			return AspectJAfterAdvice.class;
		}
		else if (AFTER_RETURNING_ELEMENT.equals(elementName)) {
			return AspectJAfterReturningAdvice.class;
		}
		else if (AFTER_THROWING_ELEMENT.equals(elementName)) {
			return AspectJAfterThrowingAdvice.class;
		}
		else if (AROUND.equals(elementName)) {
			return AspectJAroundAdvice.class;
		}
		else {
			throw new IllegalArgumentException("Unknown advice kind [" + elementName + "].");
		}
	}

	/**
	 * Parses the supplied {@code <pointcut>} and registers the resulting
	 * Pointcut with the BeanDefinitionRegistry.
	 */
	private AbstractBeanDefinition parsePointcut(Element pointcutElement, ParserContext parserContext) {
		// <1> 获取 <aop:pointcut /> 标签的 `id` 和 `expression` 配置
		String id = pointcutElement.getAttribute(ID);
		String expression = pointcutElement.getAttribute(EXPRESSION);

		AbstractBeanDefinition pointcutDefinition = null;

		try {
			this.parseState.push(new PointcutEntry(id));
			// <2> 创建一个 AspectJExpressionPointcut 类型的 RootBeanDefinition 对象
			pointcutDefinition = createPointcutDefinition(expression);
			// <3> 设置来源
			pointcutDefinition.setSource(parserContext.extractSource(pointcutElement));

			String pointcutBeanName = id;
			// <4> 注册这个 AspectJExpressionPointcut 对象
			if (StringUtils.hasText(pointcutBeanName)) {
				// <4.1> 如果 `id` 配置不为空，则取其作为名称
				parserContext.getRegistry().registerBeanDefinition(pointcutBeanName, pointcutDefinition);
			}
			else {
				// <4.2> 否则，自动生成名称，也就是取 `className`
				pointcutBeanName = parserContext.getReaderContext().registerWithGeneratedName(pointcutDefinition);
			}

			// <5> 将注册的 BeanDefinition 包装成 ComponentDefinition 放入 `parserContext` 上下文中，暂时忽略
			parserContext.registerComponent(
					new PointcutComponentDefinition(pointcutBeanName, pointcutDefinition, expression));
		}
		finally {
			this.parseState.pop();
		}

		return pointcutDefinition;
	}

	/**
	 * Parses the {@code pointcut} or {@code pointcut-ref} attributes of the supplied
	 * {@link Element} and add a {@code pointcut} property as appropriate. Generates a
	 * {@link org.springframework.beans.factory.config.BeanDefinition} for the pointcut if  necessary
	 * and returns its bean name, otherwise returns the bean name of the referred pointcut.
	 */
	@Nullable
	private Object parsePointcutProperty(Element element, ParserContext parserContext) {
		if (element.hasAttribute(POINTCUT) && element.hasAttribute(POINTCUT_REF)) {
			parserContext.getReaderContext().error(
					"Cannot define both 'pointcut' and 'pointcut-ref' on <advisor> tag.",
					element, this.parseState.snapshot());
			return null;
		}
		else if (element.hasAttribute(POINTCUT)) {
			// Create a pointcut for the anonymous pc and register it.
			String expression = element.getAttribute(POINTCUT);
			AbstractBeanDefinition pointcutDefinition = createPointcutDefinition(expression);
			pointcutDefinition.setSource(parserContext.extractSource(element));
			return pointcutDefinition;
		}
		else if (element.hasAttribute(POINTCUT_REF)) {
			String pointcutRef = element.getAttribute(POINTCUT_REF);
			if (!StringUtils.hasText(pointcutRef)) {
				parserContext.getReaderContext().error(
						"'pointcut-ref' attribute contains empty value.", element, this.parseState.snapshot());
				return null;
			}
			return pointcutRef;
		}
		else {
			parserContext.getReaderContext().error(
					"Must define one of 'pointcut' or 'pointcut-ref' on <advisor> tag.",
					element, this.parseState.snapshot());
			return null;
		}
	}

	/**
	 * Creates a {@link BeanDefinition} for the {@link AspectJExpressionPointcut} class using
	 * the supplied pointcut expression.
	 */
	protected AbstractBeanDefinition createPointcutDefinition(String expression) {
		// <1> 创建一个 AspectJExpressionPointcut 类型的 RootBeanDefinition 对象
		RootBeanDefinition beanDefinition = new RootBeanDefinition(AspectJExpressionPointcut.class);
		// <2> 设置为原型模式，需要保证每次获取到的 Pointcut 对象都是新的，防止在某些地方被修改而影响到其他地方
		beanDefinition.setScope(BeanDefinition.SCOPE_PROTOTYPE);
		// <3> 设置为是 Spring 内部合成的
		beanDefinition.setSynthetic(true);
		// <4> 添加 `expression` 属性值
		beanDefinition.getPropertyValues().add(EXPRESSION, expression);
		// <5> 返回刚创建的 RootBeanDefinition 对象
		return beanDefinition;
	}

}
