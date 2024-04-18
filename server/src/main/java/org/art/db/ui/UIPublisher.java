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
	private final RollingAverage gets  = new RollingAverage("gets");
	private final RollingAverage inserts  = new RollingAverage("inserts");
	private final RollingAverage updates  = new RollingAverage("updates");
	private final RollingAverage deletes  = new RollingAverage("deletes");
	private final RollingAverage hits  = new RollingAverage("hits");
	
	private final RollingAverage selects = new RollingAverage("selects");
	
	private Cache<String, QueryStats> queryCache = Caffeine.newBuilder().expireAfterWrite(30, TimeUnit.MINUTES).build();
	
	private Cache<String, AlertStats> alertCache = Caffeine.newBuilder().expireAfterWrite(30, TimeUnit.MINUTES).build();
	
	private Cache<String, ClientStats> clientCache = Caffeine.newBuilder().expireAfterWrite(30, TimeUnit.MINUTES).build();
	
	private final Configuration config;
	
	private final MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
	
	private final String server;
	
	private CPUMeter cpuMeter = new CPUMeter();
	
	public UIPublisher(Configuration config) {
		this.config = config;
		
		try {
			this.server = InetAddress.getLocalHost().getHostAddress() + ":" + this.config.getPort();
			this.sendSocket = new DatagramSocket();
			this.group = InetAddress.getByName("230.0.0.0");
		} catch (Exception exc) {
			throw new IllegalStateException(exc);
		}
	}

	public void clear() {
		this.gets.clear();
		this.hits.clear();
		this.selects.clear();
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
    	this.deletes.logEvent();
    }
	
	public void logGet() {
		this.gets.logEvent();
	}

	public void logInsert() {
    	this.inserts.logEvent();
    }
	
    @SuppressWarnings("deprecation")
	public void logSelect(String queryString, long time, int matches) {
		this.selects.logEvent();
		this.gets.logEvent();
		
		QueryStats stats = this.queryCache.get(queryString, x -> new QueryStats(
				    this.config.getCluster(), 
					this.config.getShard(),
					this.server, 
					escapeHtml4(x)));
		
		stats.log(1, time, matches);		
	}
	
    public void logUpdate() {
    	this.updates.logEvent();
    }
	
    
	
    private synchronized void publish() {
		try {
			long cpu = (long)(this.cpuMeter.getCPU() * 100);
			long memory = (long) (memoryBean.getHeapMemoryUsage().getUsed() / ONE_MEG);
			long maxMemory = (long) (memoryBean.getHeapMemoryUsage().getMax() / ONE_MEG);
			long redMemory = (long) (.7 * maxMemory);
			

			ServerStats stats = new ServerStats(this.config.getCluster(), 
					  							this.config.getShard(),
					  							this.server,
					                            this.config.getPort(),
					                            cpu,
					                            memory,
					                            maxMemory,
					                            redMemory,
					                            this.gets.getAverage(),
					                            this.inserts.getAverage(),
					                            this.deletes.getAverage(),
					                            this.selects.getAverage(),
					                            this.hits.getAverage(),
					                            this.updates.getAverage());
			
			this.publish(stats);
			
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
