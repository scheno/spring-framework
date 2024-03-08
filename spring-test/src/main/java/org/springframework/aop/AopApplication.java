package org.springframework.aop;

import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/**
 * @author shenchen
 * @since 2024/3/7 21:39
 */
public class AopApplication {

	public static void main(String[] args) {
		AnnotationConfigApplicationContext applicationContext = new AnnotationConfigApplicationContext();
		applicationContext.register(AspectConfiguration.class);
		applicationContext.register(DefaultEchoService.class);
		applicationContext.refresh();

		EchoService echoService = applicationContext.getBean(EchoService.class);
		echoService.execute("下单");

		applicationContext.close();
	}

}
