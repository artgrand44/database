package org.art.ui;

import java.util.List;

public class ServerStats extends Stats {
	
	private long cpu; 
	private long memory;  
	private long gets; 
	private long selects;
	private long inserts;
	private long updates;
	private long removes;
	private long hits;
	private long maxMemory;
	private long redMemory;
	private String port = "";

	public ServerStats() {

	}

	public ServerStats(String cacheName, 
		                    String shard, 
		                    String server, 
		                    String port,
		                    long cpu, 
		                    long memory, 
		                    long maxMemory,
		                    long redMemory,
		                    long gets, 
		                    long inserts,
		                    long removes,
		                    long selects, 
		                    long hits,
		                    long updates) {
		
		super(cacheName, shard, server);
		
		this.port = port;
		this.cpu = cpu;
		this.memory = memory;
		this.maxMemory = maxMemory;
		this.redMemory = redMemory;
		this.inserts = inserts;
		this.removes = removes;
		this.gets = gets;
		this.selects = selects;
		this.hits = hits;
		this.updates = updates;
	}

	public long getCpu() {
		return cpu;
	}

	public void setCpu(long cpu) {
		this.cpu = cpu;
	}

	public long getMemory() {
		return memory;
	}

	public void setMemory(long memory) {
		this.memory = memory;
	}

	public long getGets() {
		return gets;
	}

	public void setGets(long gets) {
		this.gets = gets;
	}

	public long getSelects() {
		return selects;
	}

	public void setSelects(long selects) {
		this.selects = selects;
	}

	public long getHits() {
		return hits;
	}

	public void setHits(long hits) {
		this.hits = hits;
	}

	@Override
	public <T extends Stats> Stats average(String cache, String cluster, List<T> list) {
		long size = list.size();
		long cpu = 0;
		long memory = 0;
		long gets = 0;
		long inserts = 0;
		long removes = 0;
		long selects = 0;
		long hits = 0;
		long maxMemory = 0;
		long redMemory = 0;
		long updates = 0;
		String port = "8080";

		for (Stats stats :list) {
			ServerStats serverStats = (ServerStats)stats;
					
			cpu += serverStats.getCpu();
			memory += serverStats.getMemory();
			maxMemory += serverStats.getMaxMemory();
			redMemory += serverStats.getRedMemory();
			gets += serverStats.getGets();
			inserts += serverStats.getInserts();
			removes += serverStats.getRemoves();
			selects += serverStats.getSelects();
			updates += serverStats.getUpdates();
			hits += serverStats.getHits();
			port = serverStats.getPort();
			
		}
		
		return new ServerStats(cache,
				               cluster, 
				               "", 
				               port,
				               cpu / size,
				               memory / size,
				               maxMemory / size,
				               redMemory / size,
				               gets / size,
				               inserts / size,
				               removes / size,
				               selects / size,
				               hits / size,
				               updates / size );
		

	}
	
	@Override
	public String toString() {
		return "ServerStats [shard=" + getShard() + ", server=" + getServer() + " cpu=" + cpu
				+ ", memory=" + memory + ", gets=" + gets + ", selects=" + selects + ", hits=" + hits + ",updates=" + updates
				+ ", getCacheName()=" + getCacheName() + "]";
	}

	public long getMaxMemory() {
		return maxMemory;
	}

	public void setMaxMemory(long maxMemory) {
		this.maxMemory = maxMemory;
	}

	public long getRedMemory() {
		return redMemory;
	}

	public void setRedMemory(long redMemory) {
		this.redMemory = redMemory;
	}

	public long getInserts() {
		return inserts;
	}

	public void setInserts(long puts) {
		this.inserts = puts;
	}

	public long getRemoves() {
		return removes;
	}

	public void setRemoves(long removes) {
		this.removes = removes;
	}

	public String getPort() {
		return port;
	}

	public void setPort(String port) {
		this.port = port;
	}

	public long getUpdates() {
		return updates;
	}

	public void setUpdates(long updates) {
		this.updates = updates;
	}
	
}
