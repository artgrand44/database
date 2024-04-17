package org.art.db.ui;

import java.util.List;

public class AlertStats extends Stats {
	
	private String alert;
	private long calls;

	public AlertStats() {

	}

	public AlertStats(String cacheName, 
		              String shard, 
		              String server, 
		              String alert,
		              long calls) {
		
		super(cacheName, shard, server);
		
		this.alert = alert;
		this.calls = calls;
	}

	@Override
	public <T extends Stats> Stats average(String cache, String cluster, List<T> list) {
		long calls = 0;

		for (Stats stats :list) {
			AlertStats alertStats = (AlertStats)stats;
					
			calls += alertStats.calls;
		}
		
		return new AlertStats(cache,
				               cluster, 
				               this.getShard(),
				               this.getServer(), 
				               calls / list.size());
		

	}

	@Override
	public int compareTo(Stats that) {
		AlertStats thatStats = (AlertStats)that;
		int compare = super.compareTo(that);
		
		return compare == 0 ? this.alert.compareTo(thatStats.alert) : compare;
	}
	
	public String getAlert() {
		return alert;
	}

	public void setAlert(String alert) {
		this.alert = alert;
	}

	public long getCalls() {
		return calls;
	}

	public void setCalls(long calls) {
		this.calls = calls;
	}

	@Override
	public String toString() {
		return "AlertStats [alert=" + alert + ", calls=" + calls + "]";
	}
	
		
}
