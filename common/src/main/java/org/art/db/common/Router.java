package org.art.db.common;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

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
	
	private static final ScheduledExecutorService SCHEDULER =  Scheduler.createScheduler("Rest", 10);
	
	private final ConcurrentMap<String, Batch> batches = new ConcurrentHashMap<>();
	
	private final ReentrantLock batchLock = new ReentrantLock();
	
	private static final int MAX_SIZE = 10;
	
	private static final long TIMEOUT = 100;
	
	private RestClient restClient;
	
	static record RouterKey(DiscoveryHandler discoveryHandler, String user, String password) {
		
	}
	
	private static ConcurrentMap<RouterKey, Router> routers = new ConcurrentHashMap<>();
	
	private Router(DiscoveryHandler discoveryHandler, String user, String password) {
		this.discoveryHandler = discoveryHandler;
		this.restClient = new RestClient(user, password);
	}
	
	public byte[] batch(String shardKey, URI uri, String template, byte[] body) throws Exception {
		this.batchLock.lock();
		
		Batch batch = null;
		int index = -1;
			
		try {
			String shard = this.discoveryHandler.getShard(shardKey);
			String key = this.getBatchKey(uri, shard, template);

			batch = this.batches.computeIfAbsent(key, x -> new Batch(shard, template, uri));
			index = batch.add(body);

			final Batch abatch = batch;

			if (batch.size() >= MAX_SIZE) {
				SCHEDULER.submit(() -> processBatch(abatch));
			} else if (batch.size() == 1) {
				SCHEDULER.schedule(() -> processBatch(abatch), TIMEOUT, TimeUnit.MILLISECONDS);
			}			

		} finally {
			this.batchLock.unlock();
		}
		
		try {
			return batch.future.get().get(index);
		} catch (ExecutionException exc) {
			Throwable cause = exc.getCause();
			
			if (cause == null) {
				logger.info("no cause");
			} else {
				logger.info("cause");
				cause.printStackTrace();
			}
			throw cause instanceof DBRuntimeException runtime ? runtime : new DBRuntimeException("Error processing batch", cause); 
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
	
	private String getBatchKey(Batch batch) {
		return this.getBatchKey(batch.uri, batch.shard, batch.template);
	}

	private String getBatchKey(URI uri, String shard, String template) {
		return uri == null ? shard + "|" + template : uri + "|" + shard + "|" + template;
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
	
	private void processBatch(Batch batch) {
		String key = this.getBatchKey(batch);
		boolean found;
		
		this.batchLock.lock();
		found = this.batches.remove(key, batch);
		this.batchLock.unlock();
		
		if (found) {
			try (TypedOutputStream stream = new TypedOutputStream()) {
				stream.writeUTF(batch.template);
				stream.writeInt(batch.size());
				
				for (byte[] body : batch.bodies) {
					stream.writeInt(body.length);
					stream.write(body);
				}
				
				byte[] body = stream.toByteArray();
				byte[] response;
				
				if (batch.uri == null) {
					response = this.post(batch.shard, "/db/batch", body);
				} else {
					URI uri = this.restClient.buildURI(batch.uri, "/db/batch");
					response = this.restClient.post(uri, body);
				}
				
				if (response == null || response.length == 0) {
					logger.info("bad response " + batch.template);
				} 
				
				List<byte[]> results = new ArrayList<>();
				
				try (TypedInputStream result = new TypedInputStream(response)) {
					for (int idx = 0; idx < batch.size(); idx ++) {
						try {
							byte[] buffer = new byte[result.readInt()];
						
							result.read(buffer);
							results.add(buffer);
						} catch (Exception exc) {
							logger.info("Got exception on " + idx);
						}
					}
				}
				
				batch.future.complete(results);
				
			} catch (Exception exc) {
				logger.info("Batch error on " + batch.template + " uri " + batch.uri);
				batch.future.completeExceptionally(exc);
			}
		} 
	}

	private static class Batch {
		final String shard;
		final URI uri;
		final String template;
		final CompletableFuture<List<byte[]>> future = new CompletableFuture<>();
		final List<byte[]> bodies = new ArrayList<>();
		
		Batch(String shard, String template, URI uri) {
			this.shard = shard;
			this.template = template;
			this.uri = uri;
		}
		
		int add(byte[] body) {
			this.bodies.add(body);
			return this.bodies.size() - 1;
		}
		
		int size() {
			return this.bodies.size();
		}
	}


}
