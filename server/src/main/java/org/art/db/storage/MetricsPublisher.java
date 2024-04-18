package org.art.db.storage;

public interface MetricsPublisher {
	public void logAlert(String message);
	public void logClient(String clientName, String sql, long time, long calls, int matches);
	public void logDelete();
	public void logGet();
	public void logInsert();
	public void logSelect(String queryString, long time, int matches);
	public void logUpdate();

}
