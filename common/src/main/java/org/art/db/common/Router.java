package org.art.db.common;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;

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
	
	static record DiscoveryKey(DiscoveryHandler discoveryHandler, String user, String password) {	
		
	}
	
	static record URIKey(URI uri, String user, String password) {	
		
	}
	private record BatchEntry(String uri, byte[] body, CompletableFuture<byte[]> future, long startTime) {
		
	}
	
	private RestClient restClient;
	
	/**
	 * The discovery handler for routers that use a discovery handler
	 */
	private final DiscoveryHandler discoveryHandler;
	
	/**
	 * The URI for routers than use a dedicated URI (i.e. a Kubernetes load balancer)
	 */
	private final URI uri;
	
	private final  LinkedBlockingQueue<BatchEntry> batchQueue = new  LinkedBlockingQueue<>();
	
	
	private static final ScheduledExecutorService SCHEDULER =  Scheduler.createScheduler("Rest", 10);

	
	/**
	 * A map for finding discovery handler-based routers
	 */
	private static final ConcurrentMap<DiscoveryKey, Router> discoveryRouters = new ConcurrentHashMap<>();
	
	
	/**
	 * A map for finding uri-based routers
	 */
	
	private static final ConcurrentMap<URIKey, Router> uriRouters = new ConcurrentHashMap<>();
	
	private int maxBatchSize = MAX_SIZE;
	
	private static final int MAX_SIZE = 10;
	
	
	/**
	 * Create a DiscoveryHandler based router
	 * @param discoveryHandler
	 * @param user
	 * @param password
	 */
	private Router(DiscoveryHandler discoveryHandler, String user, String password) {
		this.discoveryHandler = discoveryHandler;
		this.restClient = new RestClient(user, password);
		this.uri = null;
	}
	
	/**
	 * Create a URI based router
	 * @param uri
	 * @param user
	 * @param password
	 */
	private Router(URI uri, String user, String password) {
		this.discoveryHandler = null;
		this.restClient = new RestClient(user, password);
		this.uri = uri;
	}
	
	@SuppressWarnings({ "unchecked", "rawtypes" })
	public byte[] batch(String batchURI, String uri, byte[] body) throws Exception {
		
		// Add the request to the batch queue.  If the queue is large enough, process all of the
		// queue entries.
		
		BatchEntry entry = new BatchEntry(uri, body, (CompletableFuture<byte[]>)new CompletableFuture(), System.currentTimeMillis());
		
		this.batchQueue.add(entry);

		if (this.batchQueue.size() >= this.maxBatchSize) {
			SCHEDULER.submit(() -> processBatchQueue(batchURI));
		} 		
		
		// Wait for the queue entry to be processed.  If it times out, assume
		// that the queue never got big enough to start a batch, and process the
		// request manually.
		
		try {			
			return entry.future.get();
		} catch (ExecutionException exc) {
			throw exc.getCause() instanceof DBRuntimeException runtime ? runtime : new DBRuntimeException("Error processing batch", exc.getCause()); 
		} 
	}
	
	private byte[] doCall(String method, String shardKey, String template, Object body, Object ... args) throws Exception {
		
		if (this.uri == null) {
			URI server = null;

			for (;;) {
				try {			
					server = this.discoveryHandler.getServiceAddressForKey(shardKey == null ? UUID.randomUUID().toString() : shardKey);
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
		} else {
			return this.restClient.call(method, this.restClient.buildURI(this.uri, template), body);
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
	
	/**
	 * Find or create a DiscoveryHander-based router
	 * @param discoveryHandler
	 * @param user
	 * @param password
	 * @return
	 */
	public static Router getRouter(DiscoveryHandler discoveryHandler, String user, String password) {
		DiscoveryKey key = new DiscoveryKey(discoveryHandler, user, password);
		
		return discoveryRouters.computeIfAbsent(key, x-> new Router(discoveryHandler, user, password));
	}
	
	/**
	 * Find or create a URI-based router
	 * @param discoveryHandler
	 * @param user
	 * @param password
	 * @return
	 */
	public static Router getRouter(URI uri, String user, String password) {
		URIKey key = new URIKey(uri, user, password);
		
		return uriRouters.computeIfAbsent(key, x-> new Router(uri, user, password));
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
	
	/**
	 * Process all of the entrees on the batch queue.
	 * @param batchURI
	 */
	private void processBatchQueue(String batchURI) {
		List<BatchEntry> list = new ArrayList<>();
		
		this.batchQueue.drainTo(list);
		
		if (!list.isEmpty()) {
			try (TypedOutputStream stream = new TypedOutputStream()) {
				
				stream.writeInt(list.size());
				
				for (BatchEntry entry : list) {
					stream.writeUTF(entry.uri);
					stream.writeInt(entry.body.length);
					stream.write(entry.body);
				}
				
				byte[] body = stream.toByteArray();
				byte[] response = this.post(null, batchURI, body);
				
				try (TypedInputStream result = new TypedInputStream(response)) {
					for (BatchEntry entry : list) {
						try {
							boolean success = result.readBoolean();
							
							if (success) {
								byte[] buffer = new byte[result.readInt()];

								result.read(buffer);
								entry.future.complete(buffer);
							} else {
								String message = result.readUTF();
								
								entry.future.completeExceptionally(new DBRuntimeException(message));
							}
						} catch (Exception exc) {
							logger.error("Got exception on " + exc.getMessage());
						}
					}
				}
			} catch (Exception exc) {
				logger.error("Batch error on " + exc.getMessage());
			}
		} 
	}
	
}
