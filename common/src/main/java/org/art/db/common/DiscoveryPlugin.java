package org.art.db.common;

import java.net.URI;
import java.util.Map;


public interface DiscoveryPlugin {
	Map<URI, String> getServerInstances();
	
	default void removeInstance(URI instance) {}
		
	public void start();
	
	public String getLocalPort();
	
	public String getLocalShard();
	
	public String getDatabase();
	
	public URI getLocalInstance();
	
	public String getLocalInstanceName();
	
	public default void poll() { }
	
	void initialize(ConnectionInfo config, boolean isServer);
	
	default void clear() { }
	
	
	void stop();
}
