package org.art.db.client;

import java.io.IOException;
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
import org.art.db.common.Configuration;
import org.art.db.common.DBRuntimeException;
import org.art.db.common.DiscoveryHandler;
import org.art.db.common.DocumentSet;
import org.art.db.common.RestClient;
import org.art.db.common.RestException;
import org.art.db.common.Router;
import org.art.db.common.TypedInputStream;
import org.art.db.common.TypedOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Client {

	@SuppressWarnings("unused")
	private static final Logger logger = LoggerFactory.getLogger(Client.class);
	
	private final Router router;
	private final DiscoveryHandler handler;
	private final RestClient client;
	private final URI uri;
	private String database = "";
	private final String shard;
	private final String user;
	private final String password;
	
	public static final String WILDCARD = "*";
	
	public static final Client DUMMY = new Client();
	
	private Client()  {
		this.router = null;
		this.handler = null;
		this.client = new RestClient();
		this.uri = null;
		this.shard = WILDCARD;
		this.user = "";
		this.password = "";
	}
	
	public Client(Configuration config) throws Exception {
		this(DiscoveryHandler.getHandler(config), WILDCARD, config.getProperty("org.art.db.user"), config.getProperty("org.art.db.password"));
	}
	
	public Client(DiscoveryHandler handler, String shard, String user, String password) throws Exception {
		this.handler = handler;
		this.router = new Router(handler, "", "");
		this.client = new RestClient();
		this.uri = null;
		this.shard = shard;
		this.user = user;
		this.password = password;
	}
	
	public Client(URI uri, String shard, String user, String password) throws Exception {
		this.handler = null;
		this.router = null;
		this.client = new RestClient();
		this.uri = uri;
		this.shard = shard;
		this.user = user;
		this.password = password;
	}
	
	private byte[] buildBody(Object ... args) throws IOException {
		
		try (TypedOutputStream stream = new TypedOutputStream()) {
			for (Object arg : args) {
				stream.writeObject(arg);
			}
			
			return stream.toByteArray();
		}
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
	
	public DocumentSet executeQuery(String statement, Object... parameters) throws Exception {
		this.checkParameters(parameters);
		
		List<Object> list = new ArrayList<>(Arrays.asList(parameters));
		byte[] body = this.buildBody(this.database, this.shard, this.user, this.password, statement, list);
		
		byte[] result = this.handleRequest("/db/executeQuery", Collections.emptyMap(), body);
		
		try (TypedInputStream stream = new TypedInputStream(result)) {
			return (DocumentSet)stream.readObject();
		}
		
	}
	
	public void execute(String statement, Object ... parameters) throws Exception {
		this.checkParameters(parameters);
		
		List<Object> list = new ArrayList<>(Arrays.asList(parameters));
		byte[] body = this.buildBody(this.database, this.shard, this.user, this.password, statement, list);
		
		this.handleRequest("/db/execute", Collections.emptyMap(), body);
	}
	
	public <T> T getDAO(Class<T> iface) throws Exception {
		return DAOCompiler.getInstance().getDAO(this, iface);
	}
	
	public DiscoveryHandler getDiscoveryHandler() {
		return this.handler;
	}
	
 	private byte[] handleRequest(String url, Map<String, String> parameters, byte[] body) throws Exception {
		if (this.uri == null) {
			return this.router.post(this.shard == null ? UUID.randomUUID().toString() : this.shard, url, body);
		} else {
			return this.client.post(this.client.buildURI(this.uri, url), body);
		}
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
					Thread.sleep(2000);
				}
			}
		}
		
		throw new Exception("Timeout waiting for other server");
			
	}

}
