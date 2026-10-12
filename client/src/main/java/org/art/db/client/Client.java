package org.art.db.client;

import java.io.Closeable;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.art.db.client.dao.DAOCompiler;
import org.art.db.common.Batch;
import org.art.db.common.ClientArgs;
import org.art.db.common.ConnectionInfo;
import org.art.db.common.DBRuntimeException;
import org.art.db.common.DiscoveryHandler;
import org.art.db.common.DocumentSet;
import org.art.db.common.LightWeightCache;
import org.art.db.common.RestClient;
import org.art.db.common.RestException;
import org.art.db.common.Router;
import org.art.db.common.TypedInputStream;
import org.art.db.common.TypedOutputStream;
import org.art.db.common.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Client implements Closeable {

	@SuppressWarnings("unused")
	private static final Logger logger = LoggerFactory.getLogger(Client.class);
	
	static record QueryKey(String query, List<Object> args) {
		
	}
	
	private final Router router;
	private final RestClient client;
	private final ClientCache cache;
	private URI uri;
	private String database = "";
	private final String shard;
	private boolean batch = true;
	private AtomicBoolean closed = new AtomicBoolean();
	
	public static final String WILDCARD = "*";
	
	
	public Client(URI uri, String user, String password) throws Exception {
		this(uri, user, password, 0, null);
	}
	
	public Client(URI uri, String user, String password, int cacheSize, Duration cacheDuration) throws Exception {
		String cluster;
		String shard;
		String host;
		URI serverURI;
		String port;
		
		if (uri.getScheme().equalsIgnoreCase("database")) {
			cluster = uri.getPath().substring(1);
			shard = uri.getPath().isEmpty() ? "*" : uri.getPath().substring(1);
			serverURI = null;
			host = null;
			port = "0";
		} else {
			cluster = uri.getPath().substring(1);
			shard = "*";
			serverURI = new URI(String.format("%s://%s:%s", uri.getScheme(), uri.getHost(), uri.getPort()));
			host = uri.getHost();
			port = String.valueOf(uri.getPort());
		}
		
		ConnectionInfo info = new ConnectionInfo(host, port, shard, cluster);
		
		this.router = Router.getRouter(info, serverURI, user, password);
		this.client = new RestClient(user, password);
		this.uri = serverURI;
		this.shard = "*";	
		this.cache = ClientCache.getCache(cluster, cacheSize, cacheDuration);
	}
		
	/**
	 * Used internally by SQLUtils to forward requests to a random instance in some other shard. 
	 * 
	 * Used by SQLUtils
	 * @param handler
	 * @param shard
	 * @param user
	 * @param password
	 * @throws Exception
	 */
	public Client(DiscoveryHandler handler, String shard, String user, String password) throws Exception {
		this.router = new Router(handler, user, password);
		this.client = new RestClient(user, password);
		this.uri = null;
		this.shard = shard;
		this.cache = null;
	}
	
	public Client batch(boolean batch) {
		this.batch = batch;
		return this;
	}
	
	private void checkParameters(Object ...parameters) throws Exception {
		for (int idx = 0; idx < parameters.length; idx++) {
			Object param = parameters[idx];
			
			Objects.requireNonNull(param, "The parameters cannot be null");
			
			if (!TypedOutputStream.isSerializable(param)) {
				throw new DBRuntimeException("Parameter " + idx + " is not a valid type");
			}
		}
	}
	
	public static void clear() {
		ClientCache.clearAll();
	}
	
	@Override
	public void close() throws IOException {
		if (this.closed.compareAndSet(false,  true)) {
			if (this.cache != null) {
				this.cache.free();
			}
			
			if (this.router != null) {
				this.router.free();
			}
		}		
	}
	
	public Batch createBatch() {
		return new Batch(this.database, this.shard);
	}
	
	private byte[] doBatch(String url, Map<String, String> parameters, byte[] body) throws Exception {
 		if (this.batch && this.shard.equals("*")) {
 			return this.router.batch("/db/batch", url, body); 
 		} else {
 			return doPost(url, parameters, body);
 		}
	}
	
	private byte[] doPost(String url, Map<String, String> parameters, byte[] body) throws Exception {
		if (this.uri == null) {
			return this.router.post(this.shard == null ? UUID.randomUUID().toString() : this.shard, url, body);
		} else {
			return this.client.post(this.client.buildURI(this.uri, url), body);
		}
	}
	
	public void execute(String statement, Object ... parameters) throws Exception {
		this.checkParameters(parameters);
		
		try (TypedOutputStream stream = new TypedOutputStream()) {
			List<Object> list = new ArrayList<>(Arrays.asList(parameters));
			ClientArgs args = new ClientArgs(this.database, this.shard, statement, list);

			stream.writeObject(args);

			byte[] body = stream.toByteArray();

			String lower = statement.toLowerCase().trim();
			
			if (lower.startsWith("insert") || lower.startsWith("update") || lower.startsWith("delete")) {
				this.doBatch("/db/execute", Collections.emptyMap(), body);
			} else {
				this.doPost("/db/execute", Collections.emptyMap(), body);
			}
		}
	}
	
	public void executeBatch(Batch batch) throws Exception {
		
		try (TypedOutputStream stream = new TypedOutputStream()) {
			stream.writeObject(batch);
			
			byte[] body = stream.toByteArray();			
			this.doPost("/db/executeBatch", Collections.emptyMap(), body);			
		}
	}

	public DocumentSet executeQuery(String statement, Object ... parameters) throws Exception {
		this.checkParameters(parameters);
		
		boolean select = statement.trim().toLowerCase().startsWith("select");
		QueryKey key = new QueryKey(statement, Arrays.asList(parameters));
		DocumentSet set;
		
		if (select && this.cache != null && (set = this.cache.get(key)) != null) {
			return set;
		}
			
		try (TypedOutputStream stream = new TypedOutputStream()) {
			List<Object> list = new ArrayList<>(Arrays.asList(parameters));
			ClientArgs args = new ClientArgs(this.database, this.shard, statement, list);

			stream.writeObject(args);

			byte[] body = stream.toByteArray();
		
			byte[] result = this.doBatch("/db/executeQuery", Collections.emptyMap(), body);
		
			try (TypedInputStream istream = new TypedInputStream(result)) {
				set =  (DocumentSet)istream.readObject();
				
				if (select && this.cache != null) {
					this.cache.put(key, set);
				}
				
				return set;
			}
		}
	}
	
	
	public <T> T getDAO(Class<T> iface) throws Exception {
		return DAOCompiler.getInstance().getDAO(this, iface);
	}
	
	public void use(String database) {
		this.database = database;
	}
	
	public void waitServerCount(int count) throws Exception {
		for (int idx = 0; idx < 10; idx++) {
			try {
				this.client.get(this.client.buildURI(this.uri, "/db/wait?count=%s", count));
				return;
			} catch (RestException exc) {
				if (exc.getCause() instanceof ConnectException) {
					Utils.sleepSafely(2000);
				}
			}
		}
		
		throw new Exception("Timeout waiting for other server");
			
	}
	
	static class ClientCache extends LightWeightCache<QueryKey, DocumentSet> {
		private int referenceCount;
		
		private final static Map<String,  ClientCache> caches = new HashMap<>();
		
		public ClientCache(int size, Duration timeout) {
			super(size, timeout);
		}
		
		static synchronized ClientCache getCache(String cluster, int cacheSize, Duration cacheDuration) {
			if (cacheSize == 0) {
				return null;
			}

			ClientCache cache = caches.computeIfAbsent(cluster, _ -> new ClientCache(cacheSize, cacheDuration));

			cache.referenceCount++;

			return cache;
		}
		
		static void clearAll() {
			synchronized (caches) {
				caches.clear();
			}
		}
		void free() {
			synchronized (caches) {
				if (--this.referenceCount == 0) {
					Utils.closeSafely(this);
				}
			}
		}		
	}
}
