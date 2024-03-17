package org.art.ui;

import java.util.List;

public class ClientStats extends Stats {
	private long calls;
	private long matches;
	private long time;
	private String client;
	private String sql;
	
	public ClientStats() {
		
	}
	
	public ClientStats(String cacheName, 
                       String shard, 
                       String server,
                       String client,
                       String sql,
                       long time,
                       long calls,
                       long matches) {
		
		super(cacheName, shard, server);
		
		this.client = client;
		this.sql = sql;
		this.setTime(time);
		this.calls = calls;
		this.matches = matches;
		
	}
	
	@Override
	public <T extends Stats> Stats average(String cache, String shard, List<T> list) {
		long size = list.size();
		long time = 0;
		long calls = 0;
		long matches = 0;
		
		for (Stats stats :list) {
			ClientStats clientStats = (ClientStats)stats;
					
			time += clientStats.getTime();
			calls += clientStats.getCalls();
			matches += clientStats.getMatches();
		}
		
		return new ClientStats(this.getCacheName(),
				               this.getShard(),
				               this.getServer(),
				               this.client,
				               this.sql,
				               time / size,
				               calls / size,
				               matches / size);
		
	}

	public long getCalls() {
		return calls;
	}

	public void setCalls(long gets) {
		this.calls = gets;
	}

		public String getClient() {
		return client;
	}

	public void setClient(String client) {
		this.client = client;
	}

	@Override
	public int compareTo(Stats that) {
		ClientStats thatClient = (ClientStats)that;
		int result = this.client.compareTo(thatClient.client);
		
		return result == 0 ? super.compareTo(that) : result;
		
	}
		
	

	public long getMatches() {
		return matches;
	}

	public void setMatches(long matches) {
		this.matches = matches;
	}

	public String getSql() {
		return sql;
	}

	public void setSql(String sql) {
		this.sql = sql;
	}

	public long getTime() {
		return time;
	}

	public void setTime(long time) {
		this.time = time;
	}

	@Override
	public String toString() {
		return "ClientStats [calls=" + calls + ", matches=" + matches + ", time=" + time + ", client=" + client
				+ ", sql=" + sql + "]";
	}

	
}
