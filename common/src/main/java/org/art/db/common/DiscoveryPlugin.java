package org.art.db.common;

import java.net.URI;
import java.util.Map;


public interface DiscoveryPlugin {
	Map<URI, String> getInstances();
	
	default void removeInstance(URI instance) {}
		
	public void start();
	
	default void initialize(URI localInstance, String cacheName, String localShard, boolean isServer) { }
	
	default void clear() { }
	
	void stop();
}
