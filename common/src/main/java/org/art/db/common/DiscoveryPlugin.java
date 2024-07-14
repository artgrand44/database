package org.art.db.common;

import java.net.URI;
import java.util.Map;


public interface DiscoveryPlugin {
	Map<URI, String> getInstances();
	
	default void removeInstance(URI instance) {}
		
	public void start();
	
	public String getLocalPort();
	
	default void initialize(Configuration config, boolean isServer) { }
	
	default void clear() { }
	
	
	void stop();
}
