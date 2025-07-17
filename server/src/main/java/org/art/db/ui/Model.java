package org.art.db.ui;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.art.db.common.ConnectionInfo;
import org.art.db.common.DiscoveryHandler;
import org.art.db.common.MultiValueMap;
import org.art.db.common.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

public class Model {
	
	private static final Logger logger = LoggerFactory.getLogger(Model.class);
	
	private static final ObjectMapper MAPPER = new ObjectMapper();
	
	private final String cluster;
	private final LocalModel localModel;
	
	private Cache<String, Stats> statsCache = Caffeine.newBuilder().maximumSize(1000).build();
	
	
	public Model(ConnectionInfo config, LocalModel localModel) {
		this.cluster = config.getCluster();
		this.localModel = localModel;
	}
	
	private void addToCache(Stats[] array) {
		String key = null;
		
		for (Stats stats : array) {
			
			switch(stats) {
			case AlertStats alertStats -> key = alertStats.getShard() + "|" + alertStats.getServer() + "|" + alertStats.getAlert();
			case ClientStats clientStats -> key = clientStats.getShard() + "|" + clientStats.getServer() + "|" + clientStats.getSql();
			case QueryStats queryStats -> key = ((QueryStats)stats).getQuery();
			case ServerStats serverStats -> key = stats.getServer();
			default -> logger.info("Bad stats type " + stats.getClass().getSimpleName());
			}
			
			if (key != null) {
				this.statsCache.put(key, stats);	
			}
		}
	}
	
	
	private Set<String> getLiveInstances() {
		Set<String> result = new HashSet<>();
		
		for (List<URI> list : DiscoveryHandler.getServerInstance().getInstances().values()) {
			for (URI uri : list) {
				result.add(uri.getHost());
			}
		}
		
		return result;
	}
	@SuppressWarnings("unchecked")
	<T extends Stats> List<T> getStats(Class<T> clazz) {
		List<T> result = new ArrayList<>();
		Set<String> instances = this.getLiveInstances();
		
		for (Stats stats : statsCache.asMap().values()) {
			if (clazz.isAssignableFrom(stats.getClass()) && instances.contains(stats.getServer())) {
				result.add((T)stats);
			}
		}
		
		return result;
	}
	
	@SuppressWarnings("unchecked")
	<T extends Stats> List<T> getStats(Class<T> clazz, Function<T, String> getKey) {
		List<T> table = getStats(clazz);
		MultiValueMap<String, T> map = new MultiValueMap<>(); 
	      
		for (T stats : table) {
			String key = getKey.apply(stats);
			
			map.add(key, stats);
		}
	    
		List<T> result = new ArrayList<T>();
		
		for (Map.Entry<String, List<T>> entry : map.entrySet()) {
			List<T> stats = entry.getValue();
			
			if (!stats.isEmpty()) {
				T average = (T)stats.get(0).average(this.cluster, stats.get(0).getShard(), stats);
				
				result.add(average);
			}			
		}
		
		return result;
	}
	
	
	public List<AlertStats> getAlertStats() {
		return this.getStats(AlertStats.class);	
	}
	
	public List<ClientStats> getClientStats(Function<ClientStats, String> getKey) {
		return this.getStats(ClientStats.class, getKey);		
	}
	
	public List<QueryStats> getQueryStats() {
		return this.getStats(QueryStats.class);	
	}
	
	public List<ServerStats> getServerStats() {
		return this.getStats(ServerStats.class);
		
	}
	
	public List<ServerStats> getServerStats(Function<ServerStats, String> getKey) {
		return this.getStats(ServerStats.class, getKey);		
	}
	
	public void refresh() {

		this.addToCache(this.localModel.getStats());

		RestClient client = new RestClient();

		try {
			List<byte[]> list = client.getAll(DiscoveryHandler.getServerInstance().getAllPeers(), "/db/getstats");

			for (byte[] bytes : list) {
				Stats[] stats = MAPPER.readValue(bytes, new Stats[0].getClass());

				this.addToCache(stats);
			}
		} catch (Exception exc) {

		}

	}
}

