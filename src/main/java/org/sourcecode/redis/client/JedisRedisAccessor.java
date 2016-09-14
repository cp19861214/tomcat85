package org.sourcecode.redis.client;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.JedisSentinelPool;
import redis.clients.jedis.Protocol;
import redis.clients.util.Pool;

public class JedisRedisAccessor implements RedisAccessor {

	protected final Log log = LogFactory.getLog(getClass());

	private JedisPoolConfig jedisPoolConfig;

	private Pool<Jedis> jedisPool;

	private int database = 0;

	private String host;

	private int port;

	private String masterName;

	public JedisRedisAccessor() {

	}

	public JedisRedisAccessor(JedisPoolConfig jedisPoolConfig, String masterName, String host, int port, int database) {
		this.jedisPoolConfig = jedisPoolConfig;
		this.host = host;
		this.port = port;
		this.database = database;
		this.masterName = masterName;
	}

	@Override
	public boolean exists(final byte[] key) {

		return execute(new JedisExecutor<Boolean>() {

			public Boolean execute(Jedis jedis) {

				return jedis.exists(key);
			}

		});
	}

	public String set(byte[] key, byte[] value) {
		return set(key, value, -1);
	}

	public String set(byte[] key, byte[] value, final int expire) {
		return execute(new JedisExecutor<String>() {
			public String execute(Jedis jedis) {
				String r = jedis.set(key, value);
				if (expire > 0) {
					jedis.expire(key, expire);
				}
				return r;
			}
		});
	}

	public long expire(byte[] key, final int seconds) {

		return execute(new JedisExecutor<Long>() {

			public Long execute(Jedis jedis) {
				return jedis.expire(key, seconds);

			}

		});
	}

	public long del(final byte[]... keys) {

		return execute(new JedisExecutor<Long>() {

			public Long execute(Jedis jedis) {

				return jedis.del(keys);
			}

		});
	}

	public byte[] get(final byte[] key) {

		byte[] rawvalue = execute(new JedisExecutor<byte[]>() {

			public byte[] execute(Jedis jedis) {
				return jedis.get(key);
			}
		});
		return rawvalue;
	}

	public Set<String> keys(final String pattern) {

		Set<String> kesSet = execute(new JedisExecutor<Set<String>>() {

			public Set<String> execute(Jedis jedis) {
				// TODO Auto-generated method stub
				return jedis.keys(pattern);
			}
		});
		return kesSet;
	}

	@Override
	public long dbSize() {

		long size = execute(new JedisExecutor<Long>() {

			public Long execute(Jedis jedis) {
				return jedis.dbSize();
			}
		});
		return size;
	}

	public long setnx(final byte[] key, final byte[] member) {

		return execute(new JedisExecutor<Long>() {

			public Long execute(Jedis jedis) {
				// TODO Auto-generated method stub
				return jedis.setnx(key, member);
			}
		});
	}

	@Override
	public String flushDB() {
		// TODO Auto-generated method stub
		return execute(new JedisExecutor<String>() {

			@Override
			public String execute(Jedis jedis) {
				// TODO Auto-generated method stub
				return jedis.flushDB();
			}
		});
	}

	@Override
	public void destroy() {
		jedisPool.destroy();
		log.info("destory jedis pool ...");
	}

	@Override
	public void init(String mode) {
		if (RedisConstants.MODE_SIGNLE.equals(mode)) {
			jedisPool = new JedisPool(jedisPoolConfig, host, port, Protocol.DEFAULT_TIMEOUT, null, database);
		} else {
			jedisPool = new JedisSentinelPool(masterName, asSentinelSet(host), jedisPoolConfig,
					Protocol.DEFAULT_TIMEOUT, null, database);
		}
	}

	public static final Set<String> asSentinelSet(String sentinels) {
		return new HashSet<>(Arrays.asList(sentinels.split(",")));
	}

	<T> T execute(JedisExecutor<T> jedisExecutor) {
		Jedis jedis = null;
		T t = null;
		try {
			jedis = jedisPool.getResource();
			long dbindex = jedis.getDB();
			if (dbindex != database) {
				jedis.select(database);
			}
			t = jedisExecutor.execute(jedis);
		} catch (Exception e) {
			// throw new RuntimeException("execute redis command fail " +
			// e.getMessage());
			log.error("execute redis command fail", e);
		} finally {
			if (jedis != null) {
				jedis.close();
			}
		}
		return t;
	}

	interface JedisExecutor<T> {

		T execute(Jedis jedis);
	}

	public JedisPoolConfig getJedisPoolConfig() {
		return jedisPoolConfig;
	}

	public void setJedisPoolConfig(JedisPoolConfig jedisPoolConfig) {
		this.jedisPoolConfig = jedisPoolConfig;
	}

	public Pool<Jedis> getJedisPool() {
		return jedisPool;
	}

	public void setJedisPool(Pool<Jedis> jedisPool) {
		this.jedisPool = jedisPool;
	}

	public int getDatabase() {
		return database;
	}

	public void setDatabase(int database) {
		this.database = database;
	}

	public String getMasterName() {
		return masterName;
	}

	public void setMasterName(String masterName) {
		this.masterName = masterName;
	}

	public static void main(String[] args) {
		RedisAccessor ra = new JedisRedisAccessor(new JedisPoolConfig(), "mymaster", "172.16.97.5", 6379, 3);
		ra.init("sentinel");
		String key = "abc";
		String result = ra.set(key.getBytes(), "a".getBytes(), 5);
		byte[] value = ra.get(key.getBytes());
		System.out.println(ra.setnx(key.getBytes(), "22".getBytes()));
		System.out.println(ra.exists(key.getBytes()));
		System.out.println(result + "," + new String(value));
	}

}
