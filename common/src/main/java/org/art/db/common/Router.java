package org.art.db.common;

import java.net.URI;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utilities to handle sharding, failover, and batching for
 * REST calls.
 * @author artgrand
 *
 */
public class Router {
	private static final Logger logger = LoggerFactory.getLogger(Router.class);
	
	private DiscoveryHandler discoveryHandler;
	
	
	private RestClient restClient;
	
	public Router() {
		
	}
	
	public Router(DiscoveryHandler discoveryHandler, String user, String password) {
		this.discoveryHandler = discoveryHandler;
		this.restClient = new RestClient(user, password);
	}
	
	
	private byte[] doCall(String method, String shardKey, String template, Object body, Object ... args) throws Exception {
		URI server = null;
		
		for (;;) {
			try {			
				server = this.discoveryHandler.getServiceAddressForKey(shardKey);
				URI uri = this.restClient.buildURI(server, template, args);

				return this.restClient.call(method, uri, body);
			} catch (RestException exc) {
				if (exc.getStatus() == RestException.NO_CONNECTION || exc.getStatus() == RestException.SERVER_DOWN) {
					logger.info("doCall got fail on " + server);
					this.discoveryHandler.removeInstance(server);
				} else {
					throw exc;
				}
			} 
		}
	}
	
	public byte[] get(String shardKey, String template, Object ... args) throws Exception {
		return doCall("GET", shardKey, template, null, args);
	}
	
	public byte[] get(String shardKey, long timeout, String template, Object ... args) throws Exception {
		long end = System.currentTimeMillis() + timeout;
		
		for (;;) {
			try {
				return doCall("GET", shardKey, template, null, args);
			} catch (Exception exc) {
				if (System.currentTimeMillis() > end) {
					throw exc;
				}
			}
		}
		
		
	}	
	
	public String getShard(String key) {
		return this.discoveryHandler.getShard(key);
	}
	
	
	public byte[] post(String shardKey, String template, Object body, Object ... args) throws Exception {
		return doCall("POST", shardKey, template, body, args);		
	}

	
	
	public byte[] put(String shardKey, String template, byte[] body, Object ... args) throws Exception {
		return doCall("PUT", shardKey, template, body, args);
	}
	
	

}
