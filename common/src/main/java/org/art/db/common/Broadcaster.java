package org.art.db.common;

import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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
	static record Message(String path, byte[] body, String user, String password) {
		
	}
	
	/**
	 * A message queue for an instance that is in the middle of startup
	 */
	static record MessageQueue (String shard, LinkedList<Message> messages, long startTime, boolean temporary) {
		
		MessageQueue(String shard, boolean temporary) {
			this(shard, new LinkedList<>(), System.currentTimeMillis(), temporary);
		}
	}
	
	
	/**
	 * A map from URI's to instances for servers that are starting up.  The queue
	 * also contains entries for instances that are temporarily out of service
	 * due to network problems.
	 */
	private ConcurrentMap<URI, MessageQueue> messageQueues = new ConcurrentHashMap<>();
	
	private RestClient client = new RestClient(FAST_TIMEOUT);
	
	
	/**
	 * The TTL for broadcast requests.  
	 */
	private final static long REQUEST_TTL = 5 * 60 * 60 * 1000;
	
	
	/**
	 * The timeout for individual REST calls. 
	 * 
	 */
	private final static Duration FAST_TIMEOUT = Duration.ofMillis(100);
	
	
	private static final Broadcaster instance = new Broadcaster();
	
	private Broadcaster() {
		Scheduler.DEFAULT.scheduleAtFixedRate(this::timeoutHandler, 1, 1, TimeUnit.MINUTES);
	}
	
	
	/**
	 * Broadcast a message to all of the instances
	 * @param path
	 * @param body
	 * @throws Exception
	 */
	public void broadcast(String path, byte[] body) throws Exception {
		Message message = new Message(path, body, null, null);
		
		for (MessageQueue queue : this.messageQueues.values()) {
			queue.messages.add(message);
		}
		
		DiscoveryHandler handler = DiscoveryHandler.getServerInstance();
		List<URI> instances = new ArrayList<>();
		
		for (String shard : handler.getShards()) {
			instances.addAll(handler.getInstancesForShard(shard));
		}
		
		instances.remove(handler.getLocalInstance());
		this.broadcast(instances, path, body);
	}
	
	/**
	 * Broadcast a message to a single shard
	 * @param path
	 * @param body
	 * @param shard
	 * @throws Exception
	 */
	public void broadcast(String path, byte[] body, String shard) throws Exception {
		Message message = new Message(path, body, null, null);
		
		for (MessageQueue queue : this.messageQueues.values()) {
			if (shard.equals(queue.shard)) {
				queue.messages.add(message);
			}			
		}
		
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
		Message message = new Message(path, body, user, password);
		
		for (MessageQueue queue : this.messageQueues.values()) {
			if (shard.equals(queue.shard)) {
				queue.messages.add(message);
			}			
		}
		
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
		
		for (URI base : instances) {
			futures.add(myClient.callAsync("PUT", myClient.buildURI(base, path), body));
		}
		
		for (int idx = 0; idx < instances.size(); idx++) {
			Future<HttpResponse<byte[]>> future = futures.get(idx);
			
			try {
				future.get(FAST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
			} catch (Throwable exc) {
				if (exc.getCause() instanceof ConnectException || exc instanceof TimeoutException) {
					MessageQueue queue = this.messageQueues.computeIfAbsent(instances.get(idx), x -> new MessageQueue(null, true));

					DiscoveryHandler.getServerInstance().removeInstance(instances.get(idx));
					queue.messages.add(new Message(path, body, user, password));
				} 
			}
		}
		
		
	}
	
	/**
	 * This method is called whenever the DiscoveryHandler detects an available 
	 * instance.   It sends any messages that have been queued up for the instance.
	 * Note that there is a chicken and the egg problem during startup - which do 
	 * we do first - start the DiscoveryHandler or start the web server. To get
	 * around this, we need to send each message several times until the
	 * web server is available.
	 * @param uri
	 */
	public void markAvailable(URI uri) {
		MessageQueue queue = this.messageQueues.remove(uri);
		
		if (queue != null) {
			logger.info("got available from " + uri);
			
			for (Message message : queue.messages) {
				for (int idx = 0; idx < 10; idx++) {
					try {
						RestClient myClient = message.user == null ? new RestClient() : new RestClient(message.user, message.password);

						myClient.put(client.buildURI(uri, message.path), message.body);
						break;
					} catch (Exception exc) {	
						if (exc.getCause() instanceof ConnectException || exc instanceof TimeoutException) {
							Utils.sleepSafely(100);
							logger.info("sleeping " + idx);
						} else {
							break;
						}
					}
				}
			}
		}
		
	}
		
	public void markRecovering(URI uri, String shard) {
		messageQueues.put(uri, new MessageQueue(shard, false));
	}

	
	/**
	 * Helper method to handle timeouts.
	 */
	private void timeoutHandler() {
		Iterator<Map.Entry<URI, MessageQueue>> iterator = this.messageQueues.entrySet().iterator();
		
		while (iterator.hasNext()) {
			Map.Entry<URI, MessageQueue> entry = iterator.next();
			MessageQueue queue = entry.getValue();
			
			// If we created the queue as a result of a temporary network problem then
			// try again.
			
			if (queue.temporary) {
				URI uri = entry.getKey();
				Iterator<Message> messages = queue.messages.iterator();
				
				while (messages.hasNext()) {
					Message message = messages.next();
					
					try {
						RestClient myclient = message.user == null ? this.client : new RestClient(message.user, message.password, FAST_TIMEOUT);
						
						myclient.put(client.buildURI(uri, message.path), message.body);
						messages.remove();
					} catch (Exception exc) {						
					}
				}
			}
			
			// If the queue has been around for more than a few hours then delete it.
			
			if (queue.startTime + REQUEST_TTL < System.currentTimeMillis()) {
				iterator.remove();
			} 
		}
		
	}
	
	public static Broadcaster getInstance() {
		return instance;
	}
}
