package org.art.db.ui;


import static org.apache.commons.lang3.StringEscapeUtils.escapeHtml4;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import org.art.db.common.ApplicationContext;
import org.art.db.common.ConnectionInfo;
import org.art.db.common.DiscoveryHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;



public class LocalModel {
	
	@SuppressWarnings("unused")
	private static final Logger logger = LoggerFactory.getLogger(LocalModel.class);
	
	private Cache<String, Stats> statsCache = Caffeine.newBuilder().maximumSize(1000).build();
	private final ServerStats serverStats;
	
	private final String cluster;
	private final String shard;
	
	private final String server;
	
	
	public LocalModel(ConnectionInfo info) {
		try {
			DiscoveryHandler handler = DiscoveryHandler.getServerInstance();
			this.cluster = handler.getCluster();
			this.shard =  handler.getLocalShard();
			this.server = ApplicationContext.getInstance().getInstanceName();
			this.serverStats = new ServerStats(info.getCluster(), info.getShard(), this.server);
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
	
	public synchronized Stats[] getStats() {
		List<Stats> result = new ArrayList<>();
		
		this.serverStats.computeAverages();			
		result.add(this.serverStats);

		for (Stats stats : this.statsCache.asMap().values()) {
			stats.computeAverages();
			result.add(stats);
		}

		return result.toArray(new Stats[result.size()]);
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
	
	public void logStatus(String status) {
		this.serverStats.setStatus(status);
    }
    
    public void logUpdate() {
    	this.serverStats.logUpdate();
    }
	
	
	
}