package org.art.db.storage;

import java.io.File;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.commons.io.FileUtils;
import org.art.db.common.DiscoveryHandler;
import org.art.db.common.RestClient;
import org.art.db.common.Scheduler;
import org.art.db.security.SecurityHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class broadcasts messages to all of the instances in the cluster or all of
 * the instances in a shard.  Messages for instances that are in the middle of the
 * startup process are deferred until startup is complete.  
 */
public class Broadcaster {
	
	private static final Logger logger = LoggerFactory.getLogger(Broadcaster.class);
	
	/**
	 * A message that needs to be broadcast
	 */
	static class Message {
		URI uri;
		String path;
		long timestamp;
		int retries;
		File file;
		
		Message(URI uri, String path, byte[] body) throws Exception {
			this.uri = uri;
			this.path = path;
			this.timestamp = System.currentTimeMillis();
			this.file = File.createTempFile("msg", ".dat", Files.getRoot());
			this.file.deleteOnExit();
			
			FileUtils.writeByteArrayToFile(this.file, body);
					
		}
		
		void cleanup() {
			this.file.delete();
		}
		
		byte[] getBody() throws Exception {
			return FileUtils.readFileToByteArray(this.file);
		}
	}
	
	private final ConcurrentLinkedDeque<Message> messages = new ConcurrentLinkedDeque<>();
	
	private final ConcurrentLinkedDeque<URI> startingInstances = new ConcurrentLinkedDeque<>();
	
	private RestClient client = new RestClient(SecurityHandler.getInstance().getAdminUser(), 
                                               SecurityHandler.getInstance().getAdminPassword(), 
                                               FAST_TIMEOUT);
	
	private ScheduledFuture<?> timeoutHandler;
	/**
	 * The TTL for broadcast requests.  
	 */
	private final static long REQUEST_TTL = 5 * 60 * 60 * 1000;
	
	private final static int MAX_RETRIES = 100;
	
	
	/**
	 * The timeout for individual REST calls. 
	 * 
	 */
	private final static Duration FAST_TIMEOUT = Duration.ofMillis(10000);
	
	
	private static final Broadcaster instance = new Broadcaster();
	
	private Broadcaster() {
		this.timeoutHandler = Scheduler.DEFAULT.scheduleAtFixedRate(this::timeoutHandler, 1, 10, TimeUnit.SECONDS);
	}
	
	
	/**
	 * Tell all the other instances that we are begining the startup sequence
	 * @throws Exception
	 */
	public void beginStartup() throws Exception {
		this.broadcast("/db/beginstartup?instance=" + DiscoveryHandler.getServerInstance().getLocalInstance(), new byte[0]);
	}
	
	/**
	 * Broadcast a message to all of the instances
	 * @param path
	 * @param body
	 * @throws Exception
	 */
	public void broadcast(String path, byte[] body) throws Exception {
		
		DiscoveryHandler handler = DiscoveryHandler.getServerInstance();
		List<URI> instances = new ArrayList<>();
		
		if (handler != null) {
			handler.loadEnvironment();
			for (String shard : handler.getShards()) {
				instances.addAll(handler.getInstancesForShard(shard));
			}

			instances.remove(handler.getLocalInstance());
			instances.addAll(this.startingInstances);
			
			this.broadcast(instances, path, body);
		}
	}
	
	/**
	 * Broadcast a message to a single shard
	 * @param path
	 * @param body
	 * @param shard
	 * @throws Exception
	 */
	public void broadcast(String path, byte[] body, String shard) throws Exception {
		DiscoveryHandler handler = DiscoveryHandler.getServerInstance();
		List<URI> instances = new ArrayList<>(handler.getInstancesForShard(shard));
		
		instances.remove(handler.getLocalInstance());
		this.broadcast(instances, path, body);
		
	}


	
	/**
	 * 
	 * Broadcast a message to a list of instances.  If an instance us unavailable
	 * then put the message on a queue.
	 * @param instances
	 * @param path
	 * @param body
	 * @throws Exception
	 */
	public void broadcast(List<URI> instances, String path, byte[] body) throws Exception {
		List<Future<HttpResponse<byte[]>>> futures = new ArrayList<>();
		
		
		for (URI starter : this.startingInstances) {
			if (!instances.contains(starter)) {
				instances.add(starter);
			}
		}
		
		for (URI base : instances) {
			futures.add(this.client.callAsync("PUT", this.client.buildURI(base, path), body));				
		}
				
		for (int idx = 0; idx < instances.size(); idx++) {
			Future<HttpResponse<byte[]>> future = futures.get(idx);
			
			try {
				future.get(FAST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
			} catch (Throwable exc) {
				logger.info("error on " + DiscoveryHandler.getServerInstance().getLocalPort() + " for " + instances.get(idx) + " "+ path + " " + exc.getMessage());
				
				Throwable cause = exc.getCause();
				
				if (cause instanceof ConnectException || cause instanceof HttpTimeoutException || exc instanceof TimeoutException) {
					this.messages.addLast(new Message(instances.get(idx), path, body));
					logger.info("added to queue " + exc.getClass().getName());
					
					if (cause != null) {
						logger.info("cause " + cause);
					}
				} else {
					logger.info("no add " + exc.getClass().getName() + " cause " + exc.getCause());
					
					if (exc.getCause() != null) {
						logger.info("cause = " + exc.getCause().getClass().getName());
					}
					
					
					exc.printStackTrace();
				}
			}
		}		
		
	}
		
	
	public void handleBeginStartup(URI instance) {
		logger.info(DiscoveryHandler.getServerInstance().getLocalPort() + " got begin  from " + instance);
		this.startingInstances.add(instance);
	}
	
	
	public void stop() {
		if (this.timeoutHandler != null) {
			this.timeoutHandler.cancel(true);
			this.timeoutHandler = null;
		}
	}
	/**
	 * Helper method to handle timeouts.
	 */
	private synchronized void timeoutHandler() {
		
		Iterator<Message> iterator = this.messages.iterator();
		
		while (iterator.hasNext()) {
			Message message = iterator.next();
			
			try {
				this.client.put(client.buildURI(message.uri, message.path), message.getBody());
				
				logger.info("successful retry " + message.path);
				message.cleanup();
				iterator.remove();
			} catch (Exception exc) {	
				logger.info("failed retry " + message.path + " " + exc.getMessage());
				if (message.timestamp + REQUEST_TTL > System.currentTimeMillis() || message.retries++ > MAX_RETRIES) {
					message.cleanup();
					iterator.remove();
				}
			}
		}
		
	}
	
	public static Broadcaster getInstance() {
		return instance;
	}
}
