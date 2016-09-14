package org.sourcecode.redis.client;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;

import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisCluster;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.Protocol;
import redis.clients.jedis.exceptions.JedisClusterException;

public class JedisClusterRedisAccessor implements RedisAccessor {

	protected final Log log = LogFactory.getLog(getClass());

	private String nodes;

	private Set<HostAndPort> hostAndPorts = new HashSet<HostAndPort>();

	private JedisPoolConfig jedisPoolConfig;

	private JedisCluster jedis;

	private int timeout = Protocol.DEFAULT_TIMEOUT;

	private int maxRedirections = 6;

	public JedisClusterRedisAccessor() {

	}

	public JedisClusterRedisAccessor(JedisPoolConfig poolConfig, int maxRedirections, int timeout, String nodes) {
		this.jedisPoolConfig = poolConfig;
		this.maxRedirections = maxRedirections;
		this.timeout = timeout;
		this.nodes = nodes;
	}

	@Override
	public boolean exists(byte[] key) {
		return jedis.exists(key);
	}

	@Override
	public String set(byte[] key, byte[] value) {
		return jedis.set(key, value);
	}

	@Override
	public long del(byte[]... keys) {
		return jedis.del(keys);
	}

	@Override
	public long expire(byte[] key, int seconds) {
		return jedis.expire(key, seconds);
	}

	@Override
	public byte[] get(byte[] key) {
		return jedis.get(key);
	}

	@Override
	public long setnx(byte[] key, byte[] member) {
		return jedis.setnx(key, member);
	}

	@Override
	public Set<String> keys(String pattern) {
		throw new JedisClusterException("redis cluster don't support KEYS command  ...");
	}

	@Override
	public long dbSize() {
		throw new JedisClusterException("redis cluster don't support dbSize command  ...");
	}

	@Override
	public String flushDB() {
		throw new JedisClusterException("redis cluster don't support flushDB command  ...");
	}

	@Override
	public String set(byte[] key, byte[] value, int seconds) {
		String r = jedis.set(key, value);
		if (seconds > 0) {
			expire(key, seconds);
		}
		return r;
	}

	public void init(String mode) {
		String[] nodeList = nodes.split(",");
		for (String node : nodeList) {
			String[] hostPort = node.split(":");
			hostAndPorts.add(new HostAndPort(hostPort[0], Integer.parseInt(hostPort[1])));
		}
		jedis = new JedisCluster(hostAndPorts, timeout, maxRedirections, jedisPoolConfig);
		log.info("create redis cluster success for " + nodes);
	}

	public String getNodes() {
		return nodes;
	}

	public void setNodes(String nodes) {
		this.nodes = nodes;
	}

	public Set<HostAndPort> getHostAndPorts() {
		return hostAndPorts;
	}

	public void setHostAndPorts(Set<HostAndPort> hostAndPorts) {
		this.hostAndPorts = hostAndPorts;
	}

	public JedisPoolConfig getJedisPoolConfig() {
		return jedisPoolConfig;
	}

	public void setJedisPoolConfig(JedisPoolConfig jedisPoolConfig) {
		this.jedisPoolConfig = jedisPoolConfig;
	}

	public int getTimeout() {
		return timeout;
	}

	public void setTimeout(int timeout) {
		this.timeout = timeout;
	}

	public int getMaxRedirections() {
		return maxRedirections;
	}

	public void setMaxRedirections(int maxRedirections) {
		this.maxRedirections = maxRedirections;
	}

	@Override
	public void destroy() {
		Map<String, JedisPool> pools = jedis.getClusterNodes();
		Set<String> keys = pools.keySet();
		for (String key : keys) {
			pools.get(key).destroy();
			log.info("destory cluster pool ... " + key);
		}

	}

	public static void main(String[] args) {
		String nodes = "172.16.97.5:7000,172.16.97.5:7001,172.16.97.10:7002,172.16.97.10:7003,172.16.97.18:7004,172.16.97.18:7005";
		JedisClusterRedisAccessor jedis = new JedisClusterRedisAccessor(new DefaultJedisPoolConfig(), 6, 2000, nodes);
		jedis.init("");
		String key = "testx";
		String r = jedis.set(key.getBytes(), "1".getBytes(), 5);

		long r1 = jedis.del("abc".getBytes());
		jedis.destroy();
		System.out.println("result:" + r + "," + r1);
	}

}
