package org.art.db.ui;


import static org.apache.commons.lang3.StringEscapeUtils.escapeHtml4;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import org.art.db.common.Configuration;
import org.art.db.common.Scheduler;
import org.art.db.storage.Metrics;
import org.art.db.storage.MetricsPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;



public class UIPublisher implements MetricsPublisher {
	private static final Logger logger = LoggerFactory.getLogger(UIPublisher.class);
	
	private static final ObjectMapper MAPPER = new ObjectMapper();
	

	private static final ScheduledExecutorService SCHEDULER = Scheduler.createScheduler("UI", 3);
	private final DatagramSocket sendSocket;
	
	private final InetAddress group;
	
	private ScheduledFuture<?> publisher;
	private Cache<String, Stats> statsCache = Caffeine.newBuilder().maximumSize(1000).build();
	private final ServerStats serverStats;
	
	private final Configuration config;
	private final String cluster;
	private final String shard;
	
	private final String server;
	
	
	public UIPublisher(Configuration config) {
		this.config = config;
		
		try {
			this.cluster = this.config.getCluster();
			this.shard =  this.config.getShard();
			this.server = InetAddress.getLocalHost().getHostAddress() + ":" + this.config.getPort();
			this.sendSocket = new DatagramSocket();
			this.group = InetAddress.getByName("230.0.0.0");
			this.serverStats = new ServerStats(this.config.getCluster(), this.config.getShard(), this.server);
		} catch (Exception exc) {
			throw new IllegalStateException(exc);
		}
	}

	public void clear() {
		this.serverStats.clear();
		this.statsCache.invalidateAll();
	}
		
	private <T extends Stats> T getStats(String key, Class<T> clazz, Function<String, T> func) {
		return clazz.cast(this.statsCache.get(key, func));
	}
	
	@SuppressWarnings("deprecation")
	public void logAlert(String message) {
		
		String key = escapeHtml4(message);
		AlertStats stats = this.getStats(key, AlertStats.class, x -> new AlertStats(this.cluster, this.shard, this.server, x));
		
		stats.log(1);		
	}
	
	@SuppressWarnings("deprecation")
	public void logClient(String clientName, String sql, long time, long calls, int matches) {
		
		String key = clientName + "|" + escapeHtml4(sql);
		ClientStats stats = this.getStats(key, ClientStats.class, x -> new ClientStats(this.cluster, this.shard,this.server, clientName, sql));
	
		stats.log(time, calls, matches);
	
	}

	public void logDelete() {
    	this.serverStats.logDelete();
    }
	
	public void logGet() {
		this.serverStats.logGet();
	}

	public void logInsert() {
    	this.serverStats.logInsert();;
    }
	
    @SuppressWarnings("deprecation")
	public void logSelect(String queryString, long time, int matches) {
		this.serverStats.logSelect();
		this.serverStats.logGet();
		
		String key = escapeHtml4(queryString);
		QueryStats stats = this.getStats(key, QueryStats.class, x -> new QueryStats(this.cluster, this.shard, this.server, x));
		
		stats.log(1, time, matches);		
	}
	
    @Override
	public void logStatus(String status) {
		this.serverStats.setStatus(status);
    }
    
    public void logUpdate() {
    	this.serverStats.logUpdate();
    }
	
	
    private synchronized void publish() {
		try {
			this.serverStats.computeAverages();			
			this.publish(this.serverStats);
			
			for (Stats stats : this.statsCache.asMap().values()) {
				stats.computeAverages();
				this.publish(stats);
			}
		} catch (Exception exc) {
			logger.info("publish exception " + exc.getMessage());
		}
		
	}
    
    void publish(Stats stats) throws Exception  {
		byte[] buf = MAPPER.writeValueAsBytes(stats);
		DatagramPacket packet = new DatagramPacket(buf, buf.length, group, UIController.PORT);
		
		this.sendSocket.send(packet);
	}
     

	public void start() {
		this.publisher = SCHEDULER.scheduleAtFixedRate(this::publish, 1, 20, TimeUnit.SECONDS);
		Metrics.getInstance().addPublisher(this);
	}
	
	public void stop() {
		
		if (this.publisher != null) {
			this.publisher.cancel(true);			
		}
		
		this.logStatus("Stopped");
		this.publish();
		this.statsCache.invalidateAll();
		Metrics.getInstance().removePublisher(this);
	}
	
}