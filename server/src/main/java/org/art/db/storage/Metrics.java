package org.art.db.storage;

import java.util.ArrayList;
import java.util.List;

public class Metrics implements MetricsPublisher {
	
	
	private static Metrics instance = new Metrics();

	private List<MetricsPublisher> publishers = new ArrayList<>();
	
	public void addPublisher(MetricsPublisher publisher) {
		this.publishers.add(publisher);
	}
	
	public static Metrics getInstance() {
		return instance;
	}
	
	@Override
	public void logAlert(String message) {
		for (MetricsPublisher publisher : this.publishers) {
			publisher.logAlert(message);
		}	
	}

	@Override
	public void logClient(String clientName, String sql, long time, long calls, int matches) {
		for (MetricsPublisher publisher : this.publishers) {
			publisher.logClient(clientName, sql, time, calls, matches);
		}			
	}

	@Override
	public void logDelete() {
		for (MetricsPublisher publisher : this.publishers) {
			publisher.logDelete();
		}	
	}

	@Override
	public void logGet() {
		for (MetricsPublisher publisher : this.publishers) {
			publisher.logGet();
		}
	}

	@Override
	public void logInsert() {
		for (MetricsPublisher publisher : this.publishers) {
			publisher.logInsert();
		}
	}

	@Override
	public void logSelect(String queryString, long time, int matches) {
		for (MetricsPublisher publisher : this.publishers) {
			publisher.logSelect(queryString, time, matches);
		}
	}
	
	@Override
	public void logStatus(String status) {
		for (MetricsPublisher publisher : this.publishers) {
			publisher.logStatus(status);
		}
	}

	@Override
	public void logUpdate() {
		for (MetricsPublisher publisher : this.publishers) {
			publisher.logUpdate();
		}
	}
	
	public void removePublisher(MetricsPublisher publisher) {
		this.publishers.remove(publisher);
	}
	
}
