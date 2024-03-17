package org.art.db.ui;

import java.util.List;

public class QueryStats extends Stats{
	private String query;
	private long calls;
	private long time;
	private long matches;
	
	public QueryStats() {
		
	}
	
	public QueryStats(String cacheName, String shard, String server, String query, long calls, long time, long matches) {
		super(cacheName, shard, server);
		
		this.query = query;
		this.calls = calls;
		this.time = time;
		this.matches = matches;
	}

	public String getQuery() {
		return query;
	}

	public void setQuery(String query) {
		this.query = query;
	}

	public long getCalls() {
		return calls;
	}

	public void setCalls(long calls) {
		this.calls = calls;
	}

	public long getTime() {
		return time;
	}

	public void setTime(long comparisons) {
		this.time = comparisons;
	}

	public long getMatches() {
		return matches;
	}

	public void setMatches(long results) {
		this.matches = results;
	}
	
	@Override
	public <T extends Stats> Stats average(String cache, String shard, List<T> list) {
		long size = list.size();
		long calls = 0;
		long time = 0;
		long matches = 0;
		String query = "";
		
		for (Stats stats :list) {
			QueryStats queryStats = (QueryStats)stats;
					
			calls += queryStats.getCalls();
			time += queryStats.getTime();
			matches += queryStats.getMatches();
			query = queryStats.getQuery();
			
		}
		
		return new QueryStats(cache,
				              shard, 
				              "",
				              query,
				              calls / size,
				              time / size,
				              matches / size);
		

	}
	


	@Override
	public String toString() {
		return "QueryStats [query=" + query + ", calls=" + calls + ", comparisons=" + time + ", results="
				+ matches + ", cache=" + getCacheName() + "]";
	}

	@Override
	public int compareTo(Stats that) {
		QueryStats thoseStats = (QueryStats)that;
		
		int result = this.query.compareTo(thoseStats.query);
		
		return result == 0 ? super.compareTo(that) : result;
	}
	
	
}
