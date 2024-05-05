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
import java.util.concurrent.TimeUnit;

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
	
	static record RouterKey(DiscoveryHandler discoveryHandler, String user, String password) {	
		
	}
	
	private record BatchEntry(byte[] body, CompletableFuture<byte[]> future) {
		
	}
	
	private RestClient restClient;
	
	private final DiscoveryHandler discoveryHandler;
	
	private final  LinkedBlockingQueue<BatchEntry> batchEntries = new  LinkedBlockingQueue<>();
	
	
	private static final ScheduledExecutorService SCHEDULER =  Scheduler.createScheduler("Rest", 10);

	private static final int MAX_SIZE = 10;
	
	private static final long TIMEOUT = 100;
	
	private static ConcurrentMap<RouterKey, Router> routers = new ConcurrentHashMap<>();
	
	private Router(DiscoveryHandler discoveryHandler, String user, String password) {
		this.discoveryHandler = discoveryHandler;
		this.restClient = new RestClient(user, password);
	}
	
	@SuppressWarnings({ "unchecked", "rawtypes" })
	public byte[] batch(String batchURI, String template, byte[] body) throws Exception {
		BatchEntry entry = new BatchEntry(body, (CompletableFuture<byte[]>)new CompletableFuture());
		
		this.batchEntries.add(entry);

		if (this.batchEntries.size() >= MAX_SIZE) {
			SCHEDULER.submit(() -> processBatch(batchURI, template));
		} else if (this.batchEntries.size() == 1) {
			SCHEDULER.schedule(() -> processBatch(batchURI, template), TIMEOUT, TimeUnit.MILLISECONDS);
		}			
		
		try {
			return entry.future.get();
		} catch (ExecutionException exc) {
			throw exc.getCause() instanceof DBRuntimeException runtime ? runtime : new DBRuntimeException("Error processing batch", exc.getCause()); 
		}
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
	
	public static Router getRouter(DiscoveryHandler discoveryHandler, String user, String password) {
		RouterKey key = new RouterKey(discoveryHandler, user, password);
		
		return routers.computeIfAbsent(key, x-> new Router(discoveryHandler, user, password));
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
	
	private void processBatch(String batchURI, String template) {
		List<BatchEntry> list = new ArrayList<>();
		
		this.batchEntries.drainTo(list);
		
		if (!list.isEmpty()) {
			try (TypedOutputStream stream = new TypedOutputStream()) {
				
				stream.writeUTF(template);
				stream.writeInt(list.size());
				
				for (BatchEntry entry : list) {
					stream.writeInt(entry.body.length);
					stream.write(entry.body);
				}
				
				byte[] body = stream.toByteArray();
				String shard = this.discoveryHandler.getShard((UUID.randomUUID().toString()));
				byte[] response = this.post(shard, batchURI, body);
				
				if (response == null || response.length == 0) {
					logger.info("bad response " + template);
				} 
				
				try (TypedInputStream result = new TypedInputStream(response)) {
					for (BatchEntry entry : list) {
						try {
							byte[] buffer = new byte[result.readInt()];
						
							result.read(buffer);
							entry.future.complete(buffer);
						} catch (Exception exc) {
							logger.info("Got exception on " + exc.getMessage());
						}
					}
				}
			} catch (Exception exc) {
				logger.info("Batch error on " + template);
			}
		} 
	}
}
