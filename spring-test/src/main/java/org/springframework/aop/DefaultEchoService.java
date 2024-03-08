package org.springframework.aop;

import org.springframework.stereotype.Component;

/**
 * @author shenchen
 * @since 2024/3/7 21:41
 */
@Component
public class DefaultEchoService implements EchoService {

	@Override
	public String execute(String message) {
		System.out.println("execute message: " + message);
		return message;
	}

}
