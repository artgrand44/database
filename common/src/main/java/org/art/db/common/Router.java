package org.art.db.common;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
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
	
	static record RouterKey(ConnectionInfo info, URI uri, String user, String password) {	
		
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
	
	private final RouterKey key;
	
	private final  LinkedBlockingQueue<BatchEntry> batchQueue = new  LinkedBlockingQueue<>();
	
	private int referenceCount;
	
	
	private static final ScheduledExecutorService SCHEDULER =  Scheduler.createScheduler("Rest", 10);

	
	/**
	 * A map for finding discovery handler-based routers
	 */
	private static final Map<RouterKey, Router> routers = new HashMap<>();
	
	
	private int maxBatchSize = MAX_SIZE;
	
	private static final int MAX_SIZE = 10;
	
	/**
	 * Create a router for internal server connections
	 * @param discoveryHandler
	 * @param user
	 * @param password
	 */
	public Router(DiscoveryHandler handler, String user, String password) {
		this.discoveryHandler = handler;
		this.restClient = new RestClient(user, password);
		this.uri = null;
		this.key = null;
	}
	
	/**
	 * Create a client router
	 * @param discoveryHandler
	 * @param user
	 * @param password
	 */
	private Router(RouterKey key) {
		this.key = key;
		this.discoveryHandler = key.uri == null ?  DiscoveryHandler.getHandler(key.info) : null;
		this.restClient = new RestClient(key.user, key.password);
		this.uri = key.uri;
	}
		
	public static boolean trace = false;
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
	
	public static void clear() {
		synchronized (routers) {
			routers.clear();
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
	public static Router getRouter(ConnectionInfo info, URI uri, String user, String password) {
		synchronized (routers) {
			RouterKey key = new RouterKey(info, uri, user, password);

			Router router =  routers.computeIfAbsent(key, _-> new Router(key));

			router.referenceCount++;
			return router;
		}
	}
	
	
	public String getShard(String key) {
		return this.discoveryHandler.getShard(key);
	}
	
	public void free() {
		synchronized (routers) {
			if (--this.referenceCount == 0) {
				routers.remove(this.key);
				
				if (this.discoveryHandler != null) {
					this.discoveryHandler.free();
				}
			}
		}	
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
