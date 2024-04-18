package org.art.db.ui;


import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.art.db.common.CPUMeter;
import org.art.db.common.Configuration;
import org.art.db.common.Scheduler;
import org.art.db.storage.Metrics;
import org.art.db.storage.MetricsPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import static org.apache.commons.lang3.StringEscapeUtils.escapeHtml4;



public class UIPublisher implements MetricsPublisher {
	private static final Logger logger = LoggerFactory.getLogger(UIPublisher.class);
	
	private static final ObjectMapper MAPPER = new ObjectMapper();
	

	private static final ScheduledExecutorService SCHEDULER = Scheduler.createScheduler("StandAloneDiscovery", 3);
	private static final long ONE_MEG = 1024 * 1024;
	private final DatagramSocket sendSocket;
	
	private final InetAddress group;
	
	private ScheduledFuture<?> publisher;
	
	private Cache<String, QueryStats> queryCache = Caffeine.newBuilder().expireAfterWrite(30, TimeUnit.MINUTES).build();
	
	private Cache<String, AlertStats> alertCache = Caffeine.newBuilder().expireAfterWrite(30, TimeUnit.MINUTES).build();
	
	private Cache<String, ClientStats> clientCache = Caffeine.newBuilder().expireAfterWrite(30, TimeUnit.MINUTES).build();
	
	private final ServerStats serverStats;
	
	private final Configuration config;
	
	private final String server;
	
	
	public UIPublisher(Configuration config) {
		this.config = config;
		
		try {
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
		this.alertCache.invalidateAll();
		this.queryCache.invalidateAll();
	}
	
	private List<AlertStats> getAlertStats() {
		List<AlertStats> result = new ArrayList<>(this.alertCache.asMap().values());
		
		Collections.sort(result);
		result.forEach(x -> x.computeAverages());
		
		return result;
		
	}
	
	private List<ClientStats> getClientStats() {
		List<ClientStats> result = new ArrayList<>(this.clientCache.asMap().values());
		
		Collections.sort(result);
		result.forEach(x -> x.computeAverages());
		
		return result;
	}
		
	private List<QueryStats> getQueriesStats() {
		List<QueryStats> result = new ArrayList<>(this.queryCache.asMap().values());
		
		Collections.sort(result);
		result.forEach(x -> x.computeAverages());
		
		return result;
		
	}
	
	@SuppressWarnings("deprecation")
	public void logAlert(String message) {
		this.alertCache.get(escapeHtml4(message), x -> new AlertStats( this.config.getCluster(), 
				this.config.getShard(),
				this.server,
				x)).log(1);		
	}
	
	@SuppressWarnings("deprecation")
	public void logClient(String clientName, String sql, long time, long calls, int matches) {
		ClientStats stats = this.clientCache.get(clientName + "|" + escapeHtml4(sql), x -> new ClientStats(
			    this.config.getCluster(), 
				this.config.getShard(),
				this.server, 
				clientName,
				sql));
	
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
		
		QueryStats stats = this.queryCache.get(queryString, x -> new QueryStats(
				    this.config.getCluster(), 
					this.config.getShard(),
					this.server, 
					escapeHtml4(x)));
		
		stats.log(1, time, matches);		
	}
	
    public void logUpdate() {
    	this.serverStats.logUpdate();
    }
	
    
	
    private synchronized void publish() {
		try {
			this.serverStats.computeAverages();			
			this.publish(this.serverStats);
			
			for (AlertStats astats : this.getAlertStats()) {
				this.publish(astats);
			}
			for (QueryStats qstats : this.getQueriesStats()) {
				this.publish(qstats);
			}
			
			for (ClientStats cstats : this.getClientStats()) {
				this.publish(cstats);
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
		
		this.sendSocket.close();
	}
	
}