package org.art.db.ui;


import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;

import org.art.db.common.CPUMeter;
import org.art.db.common.Configuration;
import org.art.db.common.Scheduler;
import org.art.db.storage.Metrics;
import org.art.db.storage.MetricsPublisher;
import org.checkerframework.checker.index.qual.NonNegative;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Weigher;
import static org.apache.commons.lang3.StringEscapeUtils.escapeHtml4;

public class UIPublisher implements MetricsPublisher, Weigher<String, UIPublisher.Query> {
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
	
	private Cache<String, Query> queryCache = Caffeine.newBuilder()
                                                      .maximumWeight(Long.MAX_VALUE)
                                                      .weigher(this)
                                                      .expireAfterWrite(30, TimeUnit.MINUTES)
                                                      .build();
	
	private Cache<String, Alert> alertCache = Caffeine.newBuilder()
                                                       .maximumWeight(Long.MAX_VALUE)
                                                       .weigher((x, y) -> 1)
                                                       .expireAfterWrite(30, TimeUnit.MINUTES)
                                                       .build();
	
	private Cache<String, Client> clientCache = Caffeine.newBuilder()
                                                      .maximumWeight(Long.MAX_VALUE)
                                                      .weigher(this::weighClient)
                                                      .expireAfterWrite(30, TimeUnit.MINUTES)
                                                      .build();
	
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
	
	@SuppressWarnings("deprecation")
	private List<AlertStats> getAlertStats() {
		List<AlertStats> result = new ArrayList<>();
		
		for (Map.Entry<String, Alert> entry : this.alertCache.asMap().entrySet()) {
			String message = entry.getKey();
			Alert alert = entry.getValue();
			
			AlertStats stats = new AlertStats(this.config.getCluster(), 
					                          this.config.getShard(), 
					                          this.server,
					                          escapeHtml4(message), 
					                          alert.calls.getAverage());
			result.add(stats);			
		}
		
		Collections.sort(result);
		
		return result;
		
	}
	
	private List<ClientStats> getClientStats() {
		List<ClientStats> result = new ArrayList<>();
				
		for (Map.Entry<String, Client> entry : this.clientCache.asMap().entrySet()) {
			String key = entry.getKey();
			String tokens[] = key.split("\\|");
			Client client = entry.getValue();
			
			ClientStats stats = new ClientStats(this.config.getCluster(), 
					                          this.config.getShard(), 
					                          this.server,
					                          tokens[0],
					                          tokens[1],
					                          client.time.getAverage(),
					                          client.calls.getAverage(),
					                          client.matches.getAverage());
					                          		
			
			result.add(stats);			
		}
		
		Collections.sort(result);
		
		return result;
	}
	
	
	
	@SuppressWarnings("deprecation")
	private List<QueryStats> getQueriesStats() {
		List<QueryStats> result = new ArrayList<>();
		
		for (Map.Entry<String, Query> entry : this.queryCache.asMap().entrySet()) {
			String queryString = entry.getKey();
			Query query = entry.getValue();
			
			QueryStats stats = new QueryStats(this.config.getCluster(), 
					                          this.config.getShard(), 
					                          this.server,
					                          escapeHtml4(queryString), 
					                          query.calls.getAverage(), 
					                          query.time.getAverage(), 
					                          query.matches.getAverage());
			result.add(stats);			
		}
		
		Collections.sort(result);
		
		return result;
		
	}
	
	public void logAlert(String message) {
		this.alertCache.get(message, x -> new Alert()).calls.logEvent();;		
	}
	
	public void logClient(String clientName, String sql, long time, long calls, long matches) {
		
    	BiFunction<String, Client, Client> function = (key, client) -> {
			if (client == null) {
				client = new Client();
	    	}
	    	
			client.time.logEvent(time);
			client.calls.logEvent(calls);
			client.matches.logEvent(matches);
			
	    	return client;
		};
		
		this.clientCache.asMap().compute(clientName + "|" + sql, function);
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
	
    public void logSelect(String queryString, long time, int matches) {
		this.selects.logEvent();
		this.gets.logEvent();
		
		BiFunction<String, Query, Query> function = (key, query) -> {
			if (query == null) {
				query = new Query();
	    	}
	    	
			query.calls.logEvent(1);
			query.time.logEvent(time);
			query.matches.logEvent(matches);
			
	    	return query;
		};
		
		this.queryCache.asMap().compute(queryString, function);
		
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
     
	
	@Override
	public @NonNegative int weigh(String key, Query value) {
		return 1;
	}
	
	public int weighClient(String key, Client value) {
		return 1;
	}
	
	class Alert {
		RollingAverage calls = new RollingAverage("alertcalls");
	}
	
	class Client {
		RollingAverage time = new RollingAverage("clienttime");
		RollingAverage calls = new RollingAverage("clientqueries");
		RollingAverage matches = new RollingAverage("clientmatches");
	}
	
	class Query {
		RollingAverage calls = new RollingAverage("calls");
		RollingAverage time = new RollingAverage("comparisons");
		RollingAverage matches = new RollingAverage("matches");
	}
	
}
