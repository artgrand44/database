package org.art.db.common;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Broadcaster {
	
	private static final Logger logger = LoggerFactory.getLogger(Broadcaster.class);
	
	static record Message(String path, byte[] body, String user, String password) {
		
	}
	
	static record MessageQueue (String shard, LinkedList<Message> messages) {
		
	}
	
	
	private ConcurrentMap<URI, MessageQueue> recoveringServers = new ConcurrentHashMap<>();
	private RestClient client = new RestClient();
	
	private static final Broadcaster instance = new Broadcaster();
	
	public void broadcast(String path, byte[] body) throws Exception {
		Message message = new Message(path, body, null, null);
		
		for (MessageQueue queue : this.recoveringServers.values()) {
			queue.messages.add(message);
		}
		
		DiscoveryHandler handler = DiscoveryHandler.getServerInstance();
		List<URI> instances = new ArrayList<>();
		
		for (String shard : handler.getShards()) {
			instances.addAll(handler.getInstancesForShard(shard));
		}
		
		instances.remove(handler.getLocalInstance());
		this.client.broadcast(instances, path, body);
	}
	
	public void broadcast(String path, byte[] body, String shard) throws Exception {
		Message message = new Message(path, body, null, null);
		
		for (MessageQueue queue : this.recoveringServers.values()) {
			if (queue.shard.equals(shard)) {
				queue.messages.add(message);
			}			
		}
		
		DiscoveryHandler handler = DiscoveryHandler.getServerInstance();
		List<URI> instances = new ArrayList<>(handler.getInstancesForShard(shard));
		
		instances.remove(handler.getLocalInstance());
		this.client.broadcast(instances, path, body);
		
	}
	
	public void broadcast(String path, byte[] body, String shard, String user, String password) throws Exception {
		Message message = new Message(path, body, user, password);
		
		for (MessageQueue queue : this.recoveringServers.values()) {
			if (queue.shard.equals(shard)) {
				queue.messages.add(message);
			}			
		}
		
		DiscoveryHandler handler = DiscoveryHandler.getServerInstance();
		List<URI> instances = new ArrayList<>(handler.getInstancesForShard(shard));
		RestClient myClient = new RestClient(user, password);
		
		instances.remove(handler.getLocalInstance());
		myClient.broadcast(instances, path, body);
		
	}
	
	public void markRecovered(URI uri) {
		MessageQueue queue = this.recoveringServers.remove(uri);
		
		if (queue != null) {
			logger.info("found queue");
			for (Message message : queue.messages) {
				try {
					RestClient myClient = message.user == null ? this.client : new RestClient(message.user, message.password);
					
					logger.info("sending " + message.path);
					myClient.put(client.buildURI(uri, message.path), message.body);
				} catch (Exception exc) {
					logger.info("Exception sending message " + exc.getMessage());
					break;
				}
			}
		}
		
	}
	
	public void markRecovering(URI uri, String shard) {
		logger.info("marking " + uri + " shard " + shard);
		recoveringServers.put(uri, new MessageQueue(shard, new LinkedList<>()));
	}

	
	public static Broadcaster getInstance() {
		return instance;
	}
}
