package org.sourcecode.redis.client;

import java.util.Set;

public interface RedisAccessor {

	boolean exists(byte[] key);

	String set(byte[] key, byte[] value);

	String set(byte[] key, byte[] value, int expire);

	long del(byte[]... key);

	long expire(byte[] key, int expire);

	byte[] get(byte[] key);

	long setnx(final byte[] key, final byte[] member);

	Set<String> keys(String pattern);

	long dbSize();

	String flushDB();

	void destroy();

	void init(String mode);

}
