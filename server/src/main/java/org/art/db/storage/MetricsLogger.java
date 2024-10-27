package org.art.db.storage;

import org.art.db.common.ConnectionInfo;

public abstract class MetricsLogger {
	public abstract void logAlert(String message);
	public abstract void logClient(String clientName, String sql, long time, long calls, int matches);
	public abstract void logDelete();
	public abstract void logGet();
	public abstract void logInsert();
	public abstract void logSelect(String queryString, long time, int matches);
	public abstract void logUpdate();
	public abstract void logStatus(String status);
	public abstract void start(ConnectionInfo info);
	public abstract void stop();
	
	
	private static MetricsLogger instance = PrometheusLogger.getInstance();
	
	public static MetricsLogger getInstance() {
		return instance;
	}
	public static void setInstance(MetricsLogger instance) {
		MetricsLogger.instance = instance;
	}
	
	
}
