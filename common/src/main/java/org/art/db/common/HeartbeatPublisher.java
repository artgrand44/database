package org.art.db.common;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

public class HeartbeatPublisher {

	record Message(String cluster, String shard, int port, boolean started) {
		
	}
	
	static final ObjectMapper MAPPER = new ObjectMapper();
	
	private static final Logger logger = LoggerFactory.getLogger(HeartbeatPublisher.class);
	
	private static final ScheduledExecutorService SCHEDULER = Scheduler.createScheduler("StandAloneDiscovery", 3);
	
	private DatagramSocket sendSocket;
	private InetAddress group;
	private ScheduledFuture<?> publisher;
	private volatile boolean started;
	private final String cluster;
	private final String localShard;
	private final int port;
	
	public HeartbeatPublisher(String cluster, String localShard, int port) {
		this.cluster = cluster;
		this.localShard = localShard;
		this.port = port;
		
		try {
			this.sendSocket = new DatagramSocket();
			this.group = InetAddress.getByName("230.0.0.0");
		
		} catch (Exception exc) {
			logger.info("init exception " + exc.getMessage());
		}
	
	}
	
	public void initialize() {
		try {
			publish();

			Thread.sleep(5000);
			
			this.publisher = SCHEDULER.scheduleAtFixedRate(this::publish, 1, 20, TimeUnit.SECONDS);
		} catch (Exception exc) {
			
		}
		
	}
	
	public synchronized void publish() {
		try {
			Message message = new Message(this.cluster, this.localShard, this.port, this.started);
			
			byte[] buf = MAPPER.writeValueAsBytes(message);
			DatagramPacket packet = new DatagramPacket(buf, buf.length, group, 4445);
			
			this.sendSocket.send(packet);
		} catch (Exception exc) {
		}
		
	}

	
	public void start() {
		this.started = true;
	}
	
	public void stop() {
		if (this.publisher != null) {
			if (!this.publisher.cancel(true)) {
				logger.info("cancel failed");
			}
		}
		
		this.sendSocket.close();
	}

}
