package org.sourcecode.tomcat.session;

import java.util.Random;

import org.apache.catalina.util.StandardSessionIdGenerator;

public class RedisSessionIdGenerator extends StandardSessionIdGenerator {

	private String sessionIdPrefix = "";

	final String[] words = { "A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L", "M", "N", "O", "P", "Q", "R",
			"S", "T", "U", "V", "W", "X", "Y", "Z", "a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k", "l", "m",
			"n", "o", "p", "q", "r", "s", "t", "u", "v", "w", "x", "y", "z", "0", "1", "2", "3", "4", "5", "6", "7",
			"8", "9" };

	public RedisSessionIdGenerator() {
		super();
	}

	public RedisSessionIdGenerator(String sessionIdPrefix) {
		super();
		this.sessionIdPrefix = sessionIdPrefix;
	}

	@Override
	public String generateSessionId(String route) {
		String sessionId = super.generateSessionId(route);
		String sRand = "";
		Random random = new Random();
		// 6位随机数应该足够唯一了
		for (int i = 0; i < 6; i++) {
			String rand = words[random.nextInt(words.length)];
			sRand += rand;
		}
		return sessionIdPrefix + sessionId + sRand;
	}

	public String getSessionIdPrefix() {
		return sessionIdPrefix;
	}

	public void setSessionIdPrefix(String sessionIdPrefix) {
		this.sessionIdPrefix = sessionIdPrefix;
	}

	public static void main(String[] args) {
		RedisSessionIdGenerator i = new RedisSessionIdGenerator();
		System.out.println(i.words.length);
	}
}
