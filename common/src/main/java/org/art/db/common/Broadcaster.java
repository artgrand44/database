package org.art.db.common;

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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.commons.io.FileUtils;
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
		String user; 
		String password;
		long timestamp;
		int retries;
		File file;
		
		Message(URI uri, String path, byte[] body, String user, String password) throws Exception {
			this.uri = uri;
			this.path = path;
			this.user = user;
			this.password = password;
			this.timestamp = System.currentTimeMillis();
			this.file = File.createTempFile("msg", ".dat", new File("."));
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
	
	private RestClient client = new RestClient(FAST_TIMEOUT);
	
	
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
		Scheduler.DEFAULT.scheduleAtFixedRate(this::timeoutHandler, 1, 10, TimeUnit.SECONDS);
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
	 * Broadcast a message to a single shard
	 * @param path
	 * @param body
	 * @param shard
	 * @
	 */
	public void broadcast(String path, byte[] body, String shard, String user, String password) throws Exception {
		DiscoveryHandler handler = DiscoveryHandler.getServerInstance();
		List<URI> instances = new ArrayList<>(handler.getInstancesForShard(shard));
		
		instances.remove(handler.getLocalInstance());		
		this.broadcast(instances, path, body, user, password);
		
	}
	
	public void broadcast(List<URI> instances, String path, byte[] body)  throws Exception  {
		this.broadcast(instances, path, body, null, null);
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
	public void broadcast(List<URI> instances, String path, byte[] body, String user, String password) throws Exception {
		RestClient myClient = user == null ? this.client : new RestClient(user, password, FAST_TIMEOUT);
		List<Future<HttpResponse<byte[]>>> futures = new ArrayList<>();
		
		
		for (URI starter : this.startingInstances) {
			if (!instances.contains(starter)) {
				instances.add(starter);
			}
		}
		
		for (URI base : instances) {
			futures.add(myClient.callAsync("PUT", myClient.buildURI(base, path), body));				
		}
				
		for (int idx = 0; idx < instances.size(); idx++) {
			Future<HttpResponse<byte[]>> future = futures.get(idx);
			
			try {
				future.get(FAST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
			} catch (Throwable exc) {
				logger.info("error on " + DiscoveryHandler.getServerInstance().getLocalPort() + " for " + instances.get(idx) + " "+ path + " " + exc.getMessage());
				
				Throwable cause = exc.getCause();
				
				if (cause instanceof ConnectException || cause instanceof HttpTimeoutException || exc instanceof TimeoutException) {
					this.messages.addLast(new Message(instances.get(idx), path, body,user, password));
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
		
	/**
	 * Tell all the other instances that we have completed the startup sequence
	 * @throws Exception
	 */
	public void endStartup() throws Exception {
		this.broadcast("/db/endstartup?instance=" + DiscoveryHandler.getServerInstance().getLocalInstance(), new byte[0]);
	}
	
	public void handleBeginStartup(URI instance) {
		logger.info(DiscoveryHandler.getServerInstance().getLocalPort() + " got begin  from " + instance);
		this.startingInstances.add(instance);
	}
	
	public synchronized void handleEndStartup(URI instance) {
		Iterator<Message> iterator = this.messages.iterator();
		
		logger.info(DiscoveryHandler.getServerInstance().getLocalPort() + " got end  from " + instance);
		while (iterator.hasNext()) {
			Message message = iterator.next();
			
			if (message.uri.equals(instance)) {
				try {
					RestClient myclient = new RestClient(message.user, message.password, FAST_TIMEOUT);

					myclient.put(client.buildURI(message.uri, message.path), message.getBody());
					message.cleanup();
					iterator.remove();
				} catch (Exception exc) {	
					logger.info("failed handleEndStartup " + message.path + " " + exc.getMessage());
				}
			}
			
			this.startingInstances.remove(instance);
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
				RestClient myclient = new RestClient(message.user, message.password, FAST_TIMEOUT);
				
				myclient.put(client.buildURI(message.uri, message.path), message.getBody());
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
