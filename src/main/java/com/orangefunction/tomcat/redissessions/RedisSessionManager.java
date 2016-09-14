package com.orangefunction.tomcat.redissessions;

import java.io.IOException;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Iterator;

import org.apache.catalina.Context;
import org.apache.catalina.Lifecycle;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.LifecycleState;
import org.apache.catalina.Loader;
import org.apache.catalina.Session;
import org.apache.catalina.Valve;
import org.apache.catalina.session.ManagerBase;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.sourcecode.redis.client.DefaultJedisPoolConfig;
import org.sourcecode.redis.client.JedisClusterRedisAccessor;
import org.sourcecode.redis.client.JedisRedisAccessor;
import org.sourcecode.redis.client.RedisAccessor;
import org.sourcecode.redis.client.RedisConstants;
import org.sourcecode.tomcat.session.RedisSessionIdGenerator;

import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.Protocol;

public class RedisSessionManager extends ManagerBase implements Lifecycle {

	enum SessionPersistPolicy {
		DEFAULT, SAVE_ON_CHANGE, ALWAYS_SAVE_AFTER_REQUEST;

		static SessionPersistPolicy fromName(String name) {
			for (SessionPersistPolicy policy : SessionPersistPolicy.values()) {
				if (policy.name().equalsIgnoreCase(name)) {
					return policy;
				}
			}
			throw new IllegalArgumentException("Invalid session persist policy [" + name + "]. Must be one of "
					+ Arrays.asList(SessionPersistPolicy.values()) + ".");
		}
	}

	protected byte[] NULL_SESSION = "null".getBytes();

	private final Log log = LogFactory.getLog(RedisSessionManager.class);

	// signle node redis
	protected String host = "localhost";
	protected int port = 6379;
	protected int database = 2;

	// cluster
	protected String mode = RedisConstants.MODE_SIGNLE;
	protected String masterName = RedisConstants.MATSER_NAME;
	protected int maxRedirections = 8;
	// redis
	private RedisAccessor redisAccessor;

	protected String password = null;
	protected int timeout = Protocol.DEFAULT_TIMEOUT;
	protected String sessionIdPrefix = "";

	protected JedisPoolConfig connectionPoolConfig = new DefaultJedisPoolConfig();

	protected RedisSessionHandlerValve handlerValve;
	protected ThreadLocal<RedisSession> currentSession = new ThreadLocal<>();
	protected ThreadLocal<SessionSerializationMetadata> currentSessionSerializationMetadata = new ThreadLocal<>();
	protected ThreadLocal<String> currentSessionId = new ThreadLocal<>();
	protected ThreadLocal<Boolean> currentSessionIsPersisted = new ThreadLocal<>();

	protected Serializer serializer;

	protected static String name = "RedisSessionManager";

	protected String serializationStrategyClass = "org.sourcecode.tomcat.session.JavaSerializer";

	protected EnumSet<SessionPersistPolicy> sessionPersistPoliciesSet = EnumSet.of(SessionPersistPolicy.DEFAULT);

	public String getHost() {
		return host;
	}

	public void setHost(String host) {
		this.host = host;
	}

	public int getPort() {
		return port;
	}

	public void setPort(int port) {
		this.port = port;
	}

	public int getDatabase() {
		return database;
	}

	public void setDatabase(int database) {
		this.database = database;
	}

	public int getTimeout() {
		return timeout;
	}

	public void setTimeout(int timeout) {
		this.timeout = timeout;
	}

	public String getPassword() {
		return password;
	}

	public void setPassword(String password) {
		this.password = password;
	}

	public void setSerializationStrategyClass(String strategy) {
		this.serializationStrategyClass = strategy;
	}

	public String getSessionPersistPolicies() {
		StringBuilder policies = new StringBuilder();
		for (Iterator<SessionPersistPolicy> iter = this.sessionPersistPoliciesSet.iterator(); iter.hasNext();) {
			SessionPersistPolicy policy = iter.next();
			policies.append(policy.name());
			if (iter.hasNext()) {
				policies.append(",");
			}
		}
		return policies.toString();
	}

	public void setSessionPersistPolicies(String sessionPersistPolicies) {
		String[] policyArray = sessionPersistPolicies.split(",");
		EnumSet<SessionPersistPolicy> policySet = EnumSet.of(SessionPersistPolicy.DEFAULT);
		for (String policyName : policyArray) {
			SessionPersistPolicy policy = SessionPersistPolicy.fromName(policyName);
			policySet.add(policy);
		}
		this.sessionPersistPoliciesSet = policySet;
	}

	public boolean getSaveOnChange() {
		return this.sessionPersistPoliciesSet.contains(SessionPersistPolicy.SAVE_ON_CHANGE);
	}

	public boolean getAlwaysSaveAfterRequest() {
		return this.sessionPersistPoliciesSet.contains(SessionPersistPolicy.ALWAYS_SAVE_AFTER_REQUEST);
	}

	@Override
	public int getRejectedSessions() {
		// Essentially do nothing.
		return 0;
	}

	public void setRejectedSessions(int i) {
		// Do nothing.
	}

	@Override
	public void load() throws ClassNotFoundException, IOException {

	}

	@Override
	public void unload() throws IOException {

	}

	/**
	 * Start this component and implement the requirements of
	 * {@link org.apache.catalina.util.LifecycleBase#startInternal()}.
	 *
	 * @exception LifecycleException
	 *                if this component detects a fatal error that prevents this
	 *                component from being used
	 */
	@Override
	protected synchronized void startInternal() throws LifecycleException {
		super.startInternal();

		setState(LifecycleState.STARTING);

		Boolean attachedToValve = false;
		for (Valve valve : getContext().getPipeline().getValves()) {
			if (valve instanceof RedisSessionHandlerValve) {
				this.handlerValve = (RedisSessionHandlerValve) valve;
				this.handlerValve.setRedisSessionManager(this);
				log.info("Attached to RedisSessionHandlerValve");
				attachedToValve = true;
				break;
			}
		}

		if (!attachedToValve) {
			String error = "Unable to attach to session handling valve; sessions cannot be saved after the request without the valve starting properly.";
			log.fatal(error);
			throw new LifecycleException(error);
		}

		try {
			initializeSerializer();
		} catch (ClassNotFoundException | InstantiationException | IllegalAccessException e) {
			log.fatal("Unable to load serializer", e);
			throw new LifecycleException(e);
		}

		log.info("Will expire sessions after " + getMaxInactiveInterval() + " seconds");

		initializeDatabaseConnection();

		setSessionIdGenerator(new RedisSessionIdGenerator(sessionIdPrefix));
	}

	/**
	 * Stop this component and implement the requirements of
	 * {@link org.apache.catalina.util.LifecycleBase#stopInternal()}.
	 *
	 * @exception LifecycleException
	 *                if this component detects a fatal error that prevents this
	 *                component from being used
	 */
	@Override
	protected synchronized void stopInternal() throws LifecycleException {
		if (log.isDebugEnabled()) {
			log.debug("Stopping");
		}

		setState(LifecycleState.STOPPING);

		try {
			redisAccessor.destroy();
		} catch (Exception e) {
			log.error(e);
		}
		super.stopInternal();
	}

	public int getMaxInactiveInterval() {
		Context context = getContext();
		int sessionTimeout = 30;
		if (context != null) {
			sessionTimeout = context.getSessionTimeout();
		}
		return sessionTimeout * 60;
	}

	@Override
	public Session createSession(String sessionId) {
		RedisSession session = null;
		String jvmRoute = getJvmRoute();

		if (sessionId == null) {
			sessionId = sessionIdWithJvmRoute(generateSessionId(), jvmRoute);
		} else {
			log.warn("abnormality requestSessionId:" + sessionId);
		}

		session = (RedisSession) createEmptySession();
		session.setNew(true);
		session.setValid(true);
		session.setCreationTime(System.currentTimeMillis());
		session.setMaxInactiveInterval(getMaxInactiveInterval());
		session.setId(sessionId);
		session.tellNew();
		sessionCounter++;

		currentSession.set(session);
		currentSessionId.set(sessionId);
		currentSessionIsPersisted.set(false);
		currentSessionSerializationMetadata.set(new SessionSerializationMetadata());

		try {
			saveInternal(session, true);
		} catch (IOException ex) {
			log.error("Error saving newly created session: " + ex.getMessage());
			currentSession.set(null);
			currentSessionId.set(null);
			session = null;
		}

		return session;
	}

	private String sessionIdWithJvmRoute(String sessionId, String jvmRoute) {
		if (jvmRoute != null) {
			String jvmRoutePrefix = '.' + jvmRoute;
			return sessionId.endsWith(jvmRoutePrefix) ? sessionId : sessionId + jvmRoutePrefix;
		}
		return sessionId;
	}

	@Override
	public Session createEmptySession() {
		return new RedisSession(this);
	}

	@Override
	public void add(Session session) {
		try {
			save(session);
		} catch (IOException ex) {
			log.warn("Unable to add to session manager store: " + ex.getMessage());
			throw new RuntimeException("Unable to add to session manager store.", ex);
		}
	}

	@Override
	public Session findSession(String id) throws IOException {
		RedisSession session = null;

		if (id == null) {
			currentSessionIsPersisted.set(false);
			currentSession.set(null);
			currentSessionSerializationMetadata.set(null);
			currentSessionId.set(null);
		} else if (id.equals(currentSessionId.get())) {
			session = currentSession.get();
		} else {
			byte[] data = loadSessionDataFromRedis(id);
			if (data != null) {
				DeserializedSessionContainer container = sessionFromSerializedData(id, data);
				session = container.session;
				currentSession.set(session);
				currentSessionSerializationMetadata.set(container.metadata);
				currentSessionIsPersisted.set(true);
				currentSessionId.set(id);
			} else {
				currentSessionIsPersisted.set(false);
				currentSession.set(null);
				currentSessionSerializationMetadata.set(null);
				currentSessionId.set(null);
			}
		}

		return session;
	}

	public byte[] loadSessionDataFromRedis(String id) throws IOException {
		log.trace("Attempting to load session " + id + " from Redis");
		byte[] data = redisAccessor.get(id.getBytes());
		if (data == null) {
			log.trace("Session " + id + " not found in Redis");
		}
		return data;

	}

	public DeserializedSessionContainer sessionFromSerializedData(String id, byte[] data) throws IOException {
		log.trace("Deserializing session " + id + " from Redis");

		if (Arrays.equals(NULL_SESSION, data)) {
			log.error("Encountered serialized session " + id + " with data equal to NULL_SESSION. This is a bug.");
			throw new IOException("Serialized session data was equal to NULL_SESSION");
		}

		RedisSession session = null;
		SessionSerializationMetadata metadata = new SessionSerializationMetadata();

		try {
			session = (RedisSession) createEmptySession();

			serializer.deserializeInto(data, session, metadata);

			session.setId(id);
			session.setNew(false);
			session.setMaxInactiveInterval(getMaxInactiveInterval());
			session.access();
			session.setValid(true);
			session.resetDirtyTracking();

		} catch (ClassNotFoundException ex) {
			log.fatal("Unable to deserialize into session", ex);
			throw new IOException("Unable to deserialize into session", ex);
		}

		return new DeserializedSessionContainer(session, metadata);
	}

	public void save(Session session) throws IOException {
		save(session, false);
	}

	public void save(Session session, boolean forceSave) throws IOException {

		try {
			saveInternal(session, forceSave);
		} catch (IOException e) {
			throw e;
		} finally {

		}
	}

	protected boolean saveInternal(Session session, boolean forceSave) throws IOException {
		Boolean error = true;

		try {
			log.trace("Saving session " + session + " into Redis");

			RedisSession redisSession = (RedisSession) session;

			byte[] binaryId = redisSession.getId().getBytes();

			Boolean isCurrentSessionPersisted = this.currentSessionIsPersisted.get();
			SessionSerializationMetadata sessionSerializationMetadata = currentSessionSerializationMetadata.get();

			byte[] originalSessionAttributesHash = sessionSerializationMetadata.getSessionAttributesHash();

			byte[] sessionAttributesHash = serializer.attributesHashFrom(redisSession);

			/*
			 * System.out.println("isCurrentSessionPersisted:" +
			 * isCurrentSessionPersisted + ",check:" +
			 * Arrays.equals(originalSessionAttributesHash,
			 * sessionAttributesHash) + ",dirty:" + redisSession.isDirty() +
			 * ",meta:" + originalSessionAttributesHash + ":" +
			 * sessionAttributesHash);
			 */

			if (forceSave || redisSession.isDirty() || isCurrentSessionPersisted == null || !isCurrentSessionPersisted
					|| !Arrays.equals(originalSessionAttributesHash, sessionAttributesHash)) {

				if (sessionAttributesHash == null) {
					sessionAttributesHash = serializer.attributesHashFrom(redisSession);
				}

				SessionSerializationMetadata updatedSerializationMetadata = new SessionSerializationMetadata();
				updatedSerializationMetadata.setSessionAttributesHash(sessionAttributesHash);

				redisAccessor.set(binaryId, serializer.serializeFrom(redisSession, updatedSerializationMetadata));

				redisSession.resetDirtyTracking();
				currentSessionSerializationMetadata.set(updatedSerializationMetadata);
				currentSessionIsPersisted.set(true);
				// log.info("save session " + session.getId() + " success ");
			} else {
				log.trace("Save was determined to be unnecessary");
			}
			redisAccessor.expire(binaryId, getMaxInactiveInterval());
			error = false;

			return error;
		} catch (IOException e) {
			log.error(e.getMessage());
			throw e;
		}
	}

	@Override
	public void remove(Session session) {
		remove(session, false);
	}

	@Override
	public void remove(Session session, boolean update) {
		log.trace("Removing session ID : " + session.getId());
		try {
			redisAccessor.del(session.getId().getBytes());
		} finally {

		}
	}

	public void afterRequest() {
		RedisSession redisSession = currentSession.get();
		if (redisSession != null) {
			try {
				if (redisSession.isValid()) {
					log.trace("Request with session completed, saving session " + redisSession.getId());
					// System.out.println("开始保存:" + getSaveOnChange() + "," +
					// getAlwaysSaveAfterRequest());
					save(redisSession, getAlwaysSaveAfterRequest());
				} else {
					log.trace("HTTP Session has been invalidated, removing :" + redisSession.getId());
					remove(redisSession);
				}
			} catch (Exception e) {
				log.error("Error storing/removing session", e);
			} finally {
				currentSession.remove();
				currentSessionId.remove();
				currentSessionIsPersisted.remove();
				currentSessionSerializationMetadata.remove();
				log.trace("Session removed from ThreadLocal :" + redisSession.getIdInternal());
			}
		}
	}

	@Override
	public void processExpires() {
		// We are going to use Redis's ability to expire keys for session
		// expiration.

		// Do nothing.
	}

	private void initializeDatabaseConnection() throws LifecycleException {
		try {

			if (RedisConstants.MODE_CLUSTER.equals(mode)) {
				redisAccessor = new JedisClusterRedisAccessor(connectionPoolConfig, maxRedirections, timeout, host);
			} else {
				redisAccessor = new JedisRedisAccessor(connectionPoolConfig, masterName, host, port, database);
			}
			redisAccessor.init(mode);

			log.info("redis init success : database = " + database + ",maxWaitMillis = "
					+ connectionPoolConfig.getMaxWaitMillis() + ",connectionPoolMaxTotal = "
					+ connectionPoolConfig.getMaxTotal() + ",connectionPoolMaxIdle = "
					+ connectionPoolConfig.getMaxIdle() + ",connectionPoolMinIdle = "
					+ connectionPoolConfig.getMinIdle() + ",numTestsPerEvictionRun = "
					+ connectionPoolConfig.getNumTestsPerEvictionRun() + ",testOnBorrow = "
					+ connectionPoolConfig.getTestOnBorrow() + ",MinEvictableIdleTimeMillis = "
					+ connectionPoolConfig.getMinEvictableIdleTimeMillis() + ",TimeBetweenEvictionRunsMillis = "
					+ connectionPoolConfig.getTimeBetweenEvictionRunsMillis() + ",TestWhileIdle = "
					+ connectionPoolConfig.getTestWhileIdle() + ",sessionIdPrefix = " + sessionIdPrefix + ",host = "
					+ host + ",maxRedirections = " + maxRedirections + ",mode = " + mode);
		} catch (Exception e) {
			e.printStackTrace();
			log.error("Error connecting to Redis", e);
			throw new LifecycleException("Error connecting to Redis", e);
		}
	}

	private void initializeSerializer() throws ClassNotFoundException, IllegalAccessException, InstantiationException {
		log.info("Attempting to use serializer :" + serializationStrategyClass);
		serializer = (Serializer) Class.forName(serializationStrategyClass).newInstance();

		Loader loader = null;
		Context context = this.getContext();
		if (context != null) {
			loader = context.getLoader();
		}

		ClassLoader classLoader = null;

		if (loader != null) {
			classLoader = loader.getClassLoader();
		}
		serializer.setClassLoader(classLoader);
	}

	// Connection Pool Config Accessors

	// - from org.apache.commons.pool2.impl.GenericObjectPoolConfig

	public int getConnectionPoolMaxTotal() {
		return this.connectionPoolConfig.getMaxTotal();
	}

	public void setConnectionPoolMaxTotal(int connectionPoolMaxTotal) {
		this.connectionPoolConfig.setMaxTotal(connectionPoolMaxTotal);
	}

	public int getConnectionPoolMaxIdle() {
		return this.connectionPoolConfig.getMaxIdle();
	}

	public void setConnectionPoolMaxIdle(int connectionPoolMaxIdle) {
		this.connectionPoolConfig.setMaxIdle(connectionPoolMaxIdle);
	}

	public int getConnectionPoolMinIdle() {
		return this.connectionPoolConfig.getMinIdle();
	}

	public void setConnectionPoolMinIdle(int connectionPoolMinIdle) {
		this.connectionPoolConfig.setMinIdle(connectionPoolMinIdle);
	}

	// - from org.apache.commons.pool2.impl.BaseObjectPoolConfig

	public boolean getLifo() {
		return this.connectionPoolConfig.getLifo();
	}

	public void setLifo(boolean lifo) {
		this.connectionPoolConfig.setLifo(lifo);
	}

	public long getMaxWaitMillis() {
		return this.connectionPoolConfig.getMaxWaitMillis();
	}

	public void setMaxWaitMillis(long maxWaitMillis) {
		this.connectionPoolConfig.setMaxWaitMillis(maxWaitMillis);
	}

	public long getMinEvictableIdleTimeMillis() {
		return this.connectionPoolConfig.getMinEvictableIdleTimeMillis();
	}

	public void setMinEvictableIdleTimeMillis(long minEvictableIdleTimeMillis) {
		this.connectionPoolConfig.setMinEvictableIdleTimeMillis(minEvictableIdleTimeMillis);
	}

	public long getSoftMinEvictableIdleTimeMillis() {
		return this.connectionPoolConfig.getSoftMinEvictableIdleTimeMillis();
	}

	public void setSoftMinEvictableIdleTimeMillis(long softMinEvictableIdleTimeMillis) {
		this.connectionPoolConfig.setSoftMinEvictableIdleTimeMillis(softMinEvictableIdleTimeMillis);
	}

	public int getNumTestsPerEvictionRun() {
		return this.connectionPoolConfig.getNumTestsPerEvictionRun();
	}

	public void setNumTestsPerEvictionRun(int numTestsPerEvictionRun) {
		this.connectionPoolConfig.setNumTestsPerEvictionRun(numTestsPerEvictionRun);
	}

	public boolean getTestOnCreate() {
		return this.connectionPoolConfig.getTestOnCreate();
	}

	public void setTestOnCreate(boolean testOnCreate) {
		this.connectionPoolConfig.setTestOnCreate(testOnCreate);
	}

	public boolean getTestOnBorrow() {
		return this.connectionPoolConfig.getTestOnBorrow();
	}

	public void setTestOnBorrow(boolean testOnBorrow) {
		this.connectionPoolConfig.setTestOnBorrow(testOnBorrow);
	}

	public boolean getTestOnReturn() {
		return this.connectionPoolConfig.getTestOnReturn();
	}

	public void setTestOnReturn(boolean testOnReturn) {
		this.connectionPoolConfig.setTestOnReturn(testOnReturn);
	}

	public boolean getTestWhileIdle() {
		return this.connectionPoolConfig.getTestWhileIdle();
	}

	public void setTestWhileIdle(boolean testWhileIdle) {
		this.connectionPoolConfig.setTestWhileIdle(testWhileIdle);
	}

	public long getTimeBetweenEvictionRunsMillis() {
		return this.connectionPoolConfig.getTimeBetweenEvictionRunsMillis();
	}

	public void setTimeBetweenEvictionRunsMillis(long timeBetweenEvictionRunsMillis) {
		this.connectionPoolConfig.setTimeBetweenEvictionRunsMillis(timeBetweenEvictionRunsMillis);
	}

	public String getEvictionPolicyClassName() {
		return this.connectionPoolConfig.getEvictionPolicyClassName();
	}

	public void setEvictionPolicyClassName(String evictionPolicyClassName) {
		this.connectionPoolConfig.setEvictionPolicyClassName(evictionPolicyClassName);
	}

	public boolean getBlockWhenExhausted() {
		return this.connectionPoolConfig.getBlockWhenExhausted();
	}

	public void setBlockWhenExhausted(boolean blockWhenExhausted) {
		this.connectionPoolConfig.setBlockWhenExhausted(blockWhenExhausted);
	}

	public boolean getJmxEnabled() {
		return this.connectionPoolConfig.getJmxEnabled();
	}

	public void setJmxEnabled(boolean jmxEnabled) {
		this.connectionPoolConfig.setJmxEnabled(jmxEnabled);
	}

	public String getJmxNameBase() {
		return this.connectionPoolConfig.getJmxNameBase();
	}

	public void setJmxNameBase(String jmxNameBase) {
		this.connectionPoolConfig.setJmxNameBase(jmxNameBase);
	}

	public String getJmxNamePrefix() {
		return this.connectionPoolConfig.getJmxNamePrefix();
	}

	public void setJmxNamePrefix(String jmxNamePrefix) {
		this.connectionPoolConfig.setJmxNamePrefix(jmxNamePrefix);
	}

	public String getSessionIdPrefix() {
		return sessionIdPrefix;
	}

	public void setSessionIdPrefix(String sessionIdPrefix) {
		this.sessionIdPrefix = sessionIdPrefix;
	}

	public void setMaxRedirections(int maxRedirections) {
		this.maxRedirections = maxRedirections;
	}

	public String getMode() {
		return mode;
	}

	public void setMode(String mode) {
		this.mode = mode;
	}

	public String getMasterName() {
		return masterName;
	}

	public void setMasterName(String masterName) {
		this.masterName = masterName;
	}

}

class DeserializedSessionContainer {

	public final RedisSession session;
	public final SessionSerializationMetadata metadata;

	public DeserializedSessionContainer(RedisSession session, SessionSerializationMetadata metadata) {
		this.session = session;
		this.metadata = metadata;
	}
}
