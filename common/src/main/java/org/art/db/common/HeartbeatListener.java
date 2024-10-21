package org.art.db.common;

import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

public class HeartbeatListener {

	private static final Logger logger = LoggerFactory.getLogger(HeartbeatListener.class);
	
	record Message(String cluster, String shard, int port, boolean started) {
		
	}
	
	static final ObjectMapper MAPPER = new ObjectMapper();
		
	private MulticastSocket recieveSocket;
	private InetAddress group;
	private int port;
	private volatile URI localInstance;
	private Thread poller;
	private volatile boolean stopping;
	private String cluster;
	private HeartbeatPublisher publisher;
	
	private Cache<Object, Object> cache = Caffeine.newBuilder().expireAfterWrite(30, TimeUnit.MINUTES).build();
	
	public static boolean trace;
	
	public HeartbeatListener(HeartbeatPublisher publisher, String cluster, URI localInstance) {
		this.publisher = publisher;
		this.cluster = cluster;
		
		try {
			this.recieveSocket = new MulticastSocket(4445);
			this.group = InetAddress.getByName("230.0.0.0");
			
			InetSocketAddress address = new InetSocketAddress(group, 4445);
			NetworkInterface netInterface = NetworkInterface.getByInetAddress(this.group);
			
			this.recieveSocket.joinGroup(address, netInterface);
			
			this.localInstance = localInstance;
			this.port = localInstance == null ? 0 : localInstance.getPort();
		} catch (Exception exc) {
			logger.info("init exception " + exc.getMessage());
		}
	}
	
		
	@SuppressWarnings("unused")
	private void blast() {
		for (int idx = 0; idx < 5; idx++) {
			this.publisher.publish();
			
			Utils.sleepSafely(1000);
		}
	}
	
	public void clear() {
		this.cache.invalidateAll();
	}
	
	public Map<URI, String> getInstances() {
		Map<URI, String> result = new HashMap<>();
		
		for (Map.Entry<Object, Object> entry : this.cache.asMap().entrySet()) {
			result.put((URI)entry.getKey(), (String)entry.getValue());
		}
		
		return result;
	}

	public void intialize() {
		try {
			this.poller = new Thread(this::poll);
			this.poller.setName("Poller" + System.currentTimeMillis());
			this.poller.setDaemon(true);
			
			this.poller.start();
			Utils.sleepSafely(2 * HeartbeatPublisher.PING_SECONDS * 1000);
		} catch (Exception exc) {
			
		}
		
	}
	
	public void poll() {
		while (!stopping) {
			try {
				byte[] buf = new byte[256];
				DatagramPacket packet = new DatagramPacket(buf, buf.length);
				
				this.recieveSocket.receive(packet);
				
				InetAddress address = packet.getAddress();
				Message message = MAPPER.readValue(packet.getData(), Message.class);
				
				if (trace) {
					logger.info(this.port + " got " + message.toString());
				}
								
				if (message.cluster.equals(this.cluster)) {
					
					// If the message is from another instance that is just
					// starting up then send a blast of messages.  The instance
					// that is starting will receive one/all of them during the
					// sleep in initialize() so that it can set it's state 
					// correctly.
					
					if (this.publisher != null && !message.started) {
						if (message.port != this.port) {
							this.publisher.publish();
						}
					}
					
					if (!message.started) {
						continue;
					}
					
					String serviceName = message.shard;
					URI uri;
					
					if (address.getHostName().equals("localhost") && message.port == this.port) {
						uri = this.localInstance;
						uri = this.localInstance;
					} else {
						uri = new URI(String.format("http://%s:%s", address.getHostAddress(), message.port));
					}
					
					if (trace) logger.info(this.port + " adding " + uri + " = " + serviceName);
					
					this.cache.put(uri, serviceName);
					
					Utils.sleepSafely(500);
				}
			} catch (Exception exc) {
				logger.info("***********poll exception " + exc.getMessage());
				//break;
			}
		}
		
		Utils.closeSafely(this.recieveSocket);
		logger.info("poller existing for port " + this.port);
	}

	
	public void removeInstance(URI instance) {
		this.cache.invalidate(instance);
	}
	
	public void start() {
		this.stopping = false;
	}
	
	public void stop() {
		this.stopping = true;
	}


	public HeartbeatPublisher getPublisher() {
		return publisher;
	}


	public void setPublisher(HeartbeatPublisher publisher) {
		this.publisher = publisher;
	}

}
