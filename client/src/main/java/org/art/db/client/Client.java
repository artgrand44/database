package org.art.db.client;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.Charset;
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
	
	public static final String WILDCARD = "*";
	
	public static final Client DUMMY;
	
	static {
		try {
			DUMMY = new Client();
		} catch (Exception exc) {
			throw new IllegalStateException(exc);
		}
		
	}
	
	private Client() throws Exception {
		this(new URI(String.format("http://%s:%s", InetAddress.getLocalHost().getHostAddress(), "8080")), "*", "", "");
	}
	
	public Client(Configuration config) throws Exception {
		this(DiscoveryHandler.getHandler(config), WILDCARD, config.getProperty("org.art.db.user"), config.getProperty("org.art.db.password"));
	}
	
	public Client(DiscoveryHandler handler, String shard, String user, String password) throws Exception {
		this.handler = handler;
		this.router = Router.getRouter(handler, user, password);
		this.client = new RestClient();
		this.uri = null;
		this.shard = shard;
	}
	
	public Client(URI uri, String shard, String user, String password) throws Exception {
		this(new Configuration(uri.getHost(), String.valueOf(uri.getPort()), shard, "cluster")
				.withProperty("org.art.db.dbroot", "./dbroot")
				.withProperty("org.art.db.user", user)
				.withProperty("org.art.db.password", password));
	}
	
	private byte[] buildBody(Object ... args) throws IOException {
		
		try (TypedOutputStream stream = new TypedOutputStream()) {
			for (Object arg : args) {
				stream.writeObject(arg);
			}
			
			return stream.toByteArray();
		}
	}
	
	private String buildURI(String template, Map<String, Object> params, Object ... args) {
		List<String> pairs = new ArrayList<>();
		
		for (Map.Entry<String, Object> entry : params.entrySet()) {
			pairs.add(entry.getKey() + "=" + URLEncoder.encode(entry.getValue().toString(), Charset.forName("UTF-8")));
		}
		
		return String.format(template, args) + "?" + String.join("&", pairs);
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
	
	@SuppressWarnings("unused")
	private byte[] doGet(String url, Map<String, Object> params) throws Exception {
		params.put("database", this.database);
		params.put("shard",this.shard);
		
		String uri = this.buildURI("/db/executeQuery", params);
		
		if (this.uri == null) {
			return this.router.get(this.shard == null ? UUID.randomUUID().toString() : this.shard, uri);
		} else {
			return this.client.get(this.client.buildURI(this.uri, uri));
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
		
		List<Object> list = new ArrayList<>(Arrays.asList(parameters));
		byte[] body = this.buildBody(this.database, this.shard, statement, list);
		
		this.doPost("/db/execute", Collections.emptyMap(), body);
	}
	
	public DocumentSet executeQuery(String statement, Object... parameters) throws Exception {
		this.checkParameters(parameters);
		
		List<Object> list = new ArrayList<>(Arrays.asList(parameters));
		byte[] body = this.buildBody(this.database, this.shard, statement, list);
		
		byte[] result = this.doBatch("/db/executeQuery", Collections.emptyMap(), body);
		
		try (TypedInputStream stream = new TypedInputStream(result)) {
			return (DocumentSet)stream.readObject();
		}	
	}
	
	
	public <T> T getDAO(Class<T> iface) throws Exception {
		return DAOCompiler.getInstance().getDAO(this, iface);
	}
	
	public DiscoveryHandler getDiscoveryHandler() {
		return this.handler;
	}
	

 	private byte[] doBatch(String url, Map<String, String> parameters, byte[] body) throws Exception {
		return this.router.batch(this.shard == null ? UUID.randomUUID().toString() : this.shard, this.uri, url, body);
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
