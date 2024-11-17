package org.art.db.client;

import java.net.ConnectException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.art.db.client.dao.DAOCompiler;
import org.art.db.common.ConnectionInfo;
import org.art.db.common.DBRuntimeException;
import org.art.db.common.DiscoveryHandler;
import org.art.db.common.DocumentSet;
import org.art.db.common.RestClient;
import org.art.db.common.RestException;
import org.art.db.common.Router;
import org.art.db.common.TypedInputStream;
import org.art.db.common.TypedOutputStream;
import org.art.db.common.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Client {

	@SuppressWarnings("unused")
	private static final Logger logger = LoggerFactory.getLogger(Client.class);
	
	private final Router router;
	private final DiscoveryHandler handler;
	private final RestClient client;
	private URI uri;
	private String database = "";
	private final String shard;
	private boolean batch = true;
	
	public static final String WILDCARD = "*";
	
	public Client(URI uri, String user, String password) throws Exception {
		String database;
		String shard;
		String host;
		URI serverURI;
		String port;
		
		if (uri.getScheme().equalsIgnoreCase("database")) {
			database = uri.getPath().substring(1);
			shard = uri.getPath().isEmpty() ? "*" : uri.getPath().substring(1);
			serverURI = null;
			host = null;
			port = "0";
		} else {
			database = uri.getPath().substring(1);
			shard = "*";
			serverURI = new URI(String.format("%s://%s:%s", uri.getScheme(), uri.getHost(), uri.getPort()));
			host = uri.getHost();
			port = String.valueOf(uri.getPort());
		}
		
		ConnectionInfo info = new ConnectionInfo(host, port, shard, database);
		
		this.handler = DiscoveryHandler.getHandler(info);
		this.router = Router.getRouter(handler, user, password);
		this.client = new RestClient(user, password);
		this.uri = serverURI;
		this.shard = "*";	
	}
	
	/**
	 * Used by tests that need to talk to a particular instance.
	 * @param uri
	 * @param shard
	 * @param user
	 * @param password
	 * @throws Exception
	 */
	public Client(URI uri, String shard, String user, String password) throws Exception {
		String host = uri == null ? null : uri.getHost();
		ConnectionInfo info = new ConnectionInfo(host, String.valueOf(uri.getPort()), shard, "cluster");
		
		this.handler = DiscoveryHandler.getHandler(info);
		this.router = Router.getRouter(handler, user, password);
		this.client = new RestClient(user, password);
		this.uri = uri;
		this.shard = shard;	
	}
	
	/**
	 * Used internally by SQLUtils to forward a specific instance in the same shard.
	 * @param uri
	 * @param shard
	 * @param user
	 * @param password
	 * @throws Exception
	 */
	public Client(DiscoveryHandler handler, URI uri, String shard, String user, String password) throws Exception {
		this.handler = handler;
		this.router = Router.getRouter(handler, user, password);
		this.client = new RestClient(user, password);
		this.uri = uri;
		this.shard = shard;	
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
		this.handler = handler;
		this.router = Router.getRouter(handler, user, password);
		this.client = new RestClient(user, password);
		this.uri = null;
		this.shard = shard;
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
	
	public Batch createBatch() {
		return new Batch(this.database, this.shard);
	}
	
	private byte[] doBatch(String url, Map<String, String> parameters, byte[] body) throws Exception {
 		if (this.batch && this.uri == null && this.shard.equals("*")) {
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

			new ClientArgs(this.database, this.shard, statement, list).serialize(stream);
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
			batch.serialize(stream);
			
			byte[] body = stream.toByteArray();
			this.doPost("/db/executeBatch", Collections.emptyMap(), body);			
		}
	}

	public DocumentSet executeQuery(String statement, Object ... parameters) throws Exception {
		this.checkParameters(parameters);
		
		try (TypedOutputStream stream = new TypedOutputStream()) {
			List<Object> list = new ArrayList<>(Arrays.asList(parameters));

			new ClientArgs(this.database, this.shard, statement, list).serialize(stream);
			byte[] body = stream.toByteArray();
		
			byte[] result = this.doBatch("/db/executeQuery", Collections.emptyMap(), body);
		
			try (TypedInputStream istream = new TypedInputStream(result)) {
				return (DocumentSet)istream.readObject();
			}
		}
	}
	
	
	public <T> T getDAO(Class<T> iface) throws Exception {
		return DAOCompiler.getInstance().getDAO(this, iface);
	}
	
	public DiscoveryHandler getDiscoveryHandler() {
		return this.handler;
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

}
