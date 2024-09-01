package org.art.db.common;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
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
	static class MessageQueue {
		
		final String shard;
		final long startTime;
		final boolean temporary;
		final File file;
		private DataInputStream inStream;
		private DataOutputStream outStream;
		private int size;
		
		
		MessageQueue(String shard, boolean temporary)  {
			try {
				this.shard = shard;
				this.temporary = temporary;
				this.startTime = System.currentTimeMillis();
				this.file = File.createTempFile("msg", ".dat", new File("."));
				this.outStream = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(this.file)));

				this.file.deleteOnExit();
			} catch (Exception exc) {
				throw new IllegalStateException(exc);
			}
			
		}
		
		void add(Message message) throws Exception {
			this.outStream.writeUTF(message.path);
			this.outStream.writeUTF(message.user == null ? "" : message.user);
			this.outStream.writeUTF(message.password == null ? "" : message.password);
			this.outStream.writeInt(message.body.length);
			this.outStream.write(message.body);
			this.size++;
		}
		
		
		void close() {
			Utils.closeSafely(this.inStream);
			Utils.closeSafely(this.outStream);
			this.file.delete();
		}
		void flip() throws Exception {
			this.outStream.close();
			this.outStream = null;
			this.inStream = new DataInputStream(new BufferedInputStream(new FileInputStream(file)));
		}
		
		Message read() throws Exception {
			try {
				String path = this.inStream.readUTF();
				String user = this.inStream.readUTF();
				String password = this.inStream.readUTF();
				int length = this.inStream.readInt();
				byte[] body = new byte[length];
				
				this.inStream.read(body);
				
				return new Message(path, body, user.isEmpty() ? null : user, password.isEmpty() ? null : password);				
			} catch (EOFException exc) {
				return null;
			}
		}
		
		int size() {
			return this.size;
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
			queue.add(message);
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
				queue.add(message);
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
				queue.add(message);
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

					queue.add(new Message(path, body, user, password));
					
					if (queue.size() > 2) {
						DiscoveryHandler.getServerInstance().removeInstance(instances.get(idx));
					}
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
	public void markAvailable(URI uri, String localPort) {
		MessageQueue queue = this.messageQueues.remove(uri);
		
		if (localPort.equals("8081") && uri.getPath().equals("8080")) {
			logger.info("8081 got markAvailable from 8080 " + queue);
		}
		
		if (queue != null) {
			logger.info("got markAvailable from " + uri + " sent to " + localPort);
			
			try {
				queue.flip();

				Message message;

				while ((message = queue.read()) != null) {
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
				
				queue.close();
			} catch (Exception exc) {
				logger.info("There was an exception reading the queue " + exc.getMessage());
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
				
				try {
					Message message;
					
					while ((message = queue.read()) != null) {
						try {
							RestClient myclient = new RestClient(message.user, message.password, FAST_TIMEOUT);
							
							myclient.put(client.buildURI(uri, message.path), message.body);
						} catch (Exception exc) {						
						}
					}
					
					queue.close();
				} catch (Exception exc) {
					logger.info("There was an exception reading the queue " + exc);
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
