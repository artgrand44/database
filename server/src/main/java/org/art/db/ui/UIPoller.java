package org.art.db.ui;

import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import org.art.db.common.Configuration;
import org.art.db.common.MultiValueMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Weigher;

public class UIPoller implements Weigher<String, Stats> {
	
	private static final Logger logger = LoggerFactory.getLogger(UIPoller.class);
	
	private static final ObjectMapper MAPPER = new ObjectMapper();
	
	private final MulticastSocket recieveSocket;
	private final InetAddress group;
	private Thread poller;
	private final String cluster;
	private volatile boolean stopping;
	
	private Cache<String,Stats> statsCache = Caffeine.newBuilder()
			                                    .weigher(this)
			                                    .maximumWeight(Long.MAX_VALUE)
			                                    .expireAfterWrite(30, TimeUnit.MINUTES)
			                                    .build();
	
	public UIPoller(Configuration config) {
		this.cluster = config.getCluster();
		
		try {
			this.recieveSocket = new MulticastSocket(UIController.PORT);
			this.group = InetAddress.getByName("230.0.0.0");
			
			InetSocketAddress address = new InetSocketAddress(group, UIController.PORT);
			NetworkInterface netInterface = NetworkInterface.getByInetAddress(this.group);
			
			this.recieveSocket.joinGroup(address, netInterface);
		} catch (Exception exc) {
			throw new IllegalStateException(exc);
		}
	}
	
	@SuppressWarnings("unchecked")
	<T extends Stats> List<T> getStats(Class<T> clazz) {
		List<T> result = new ArrayList<>();
		
		for (Stats stats : statsCache.asMap().values()) {
			if (clazz.isAssignableFrom(stats.getClass())) {
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
	
	
	
	private void poll() {
		while (!stopping) {
			
			try {
				byte[] buf = new byte[1000];
				DatagramPacket packet = new DatagramPacket(buf, buf.length);
				
				this.recieveSocket.receive(packet);
				
				Stats stats;
				
				try {
					stats = MAPPER.readValue(packet.getData(), Stats.class);
				} catch (Exception exc) {
					logger.info("exception deserializing packet " + exc.getMessage());
					continue;
				}
								
				if (stats.getCacheName().equals(this.cluster)) {
					String key;
					
					if (stats instanceof ServerStats serverStats) {
						key = stats.getServer();
					} else if (stats instanceof AlertStats alertStats) {
						key = alertStats.getShard() + "|" + alertStats.getServer() + "|" + alertStats.getAlert();
					} else if (stats instanceof ClientStats clientStats) {
						key = clientStats.getShard() + "|" + clientStats.getServer() + "|" + clientStats.getSql();
					} else {
						key = ((QueryStats)stats).getQuery();
					}
					
					this.statsCache.put(key, stats);	
				}
			} catch (Exception exc) {
				logger.info("poll exception " + exc.getMessage());
				break;
			}
		}
		
		this.recieveSocket.close();
		logger.info("poller existing");
	}
	
	public void start() {		
		this.poller = new Thread(this::poll);
		this.poller.setName("Poller" + System.currentTimeMillis());
		this.poller.setDaemon(true);
		this.poller.start();
	}

	public void stop() {
		this.stopping = true;
	}
	
	@Override
	public int weigh(String key, Stats value) {
		return 1;
	}
}
