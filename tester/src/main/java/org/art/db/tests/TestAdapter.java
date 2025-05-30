package org.art.db.tests;

import java.net.URI;
import java.time.Duration;

import org.art.db.client.Client;

public abstract class TestAdapter {
	public abstract URI startPrimary(String profile);
	public abstract void stopPrimary();
	public abstract URI startSecondary(String profile);
	public abstract void stopSecondary();
	public abstract Client getClusterClient(String user, String password) throws Exception;
	public abstract Client getClusterClient(String user, String password, int cacheSize, Duration cacheDuration) throws Exception;
	public abstract Client getPrimaryClient(String user, String password) throws Exception;
	public abstract Client getSecondaryClient(String user, String password) throws Exception;
	public abstract URI getPrimaryURI();
	public abstract URI getSecondardURI();
	
	public void killAll() {}
	
	private static TestAdapter instance;
	
	public static TestAdapter getInstance() {
		return instance;
	}
	
	public static void setInstance(TestAdapter instance) {
		TestAdapter.instance = instance;
	}

}
