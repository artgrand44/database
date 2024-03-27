package org.art.db.common;

import java.net.URI;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DiscoveryPluginImpl implements DiscoveryPlugin {

	@SuppressWarnings("unused")
	private static final Logger logger = LoggerFactory.getLogger(DiscoveryPluginImpl.class);
			
	private HeartbeatPublisher publisher;
	
	private HeartbeatListener listener;
	
	public DiscoveryPluginImpl() {
		
	}
	
	@Override
	public void clear() {
		this.listener.clear();
	}
	
	@Override
	public Map<URI, String> getInstances() {
		return this.listener.getInstances();
	}

	public void initialize(URI localInstance, String cluster, String localShard, boolean isServer) { 
		this.listener = new HeartbeatListener(this.publisher, cluster, localInstance);
		
		this.listener.intialize();
		this.listener.start();
		
		if (isServer) {
			this.publisher = new HeartbeatPublisher(cluster, localShard, localInstance.getPort());
			this.publisher.initialize();
			this.listener.setPublisher(this.publisher);
		}	
	}
	
	@Override
	public void removeInstance(URI instance) {
		this.listener.removeInstance(instance);
	}
	
	@Override
	public void start() {
		if (this.publisher != null) {
			this.publisher.start();
		}
	}
	
	
	@Override
	public void stop() {
		if (this.listener != null) {
			this.listener.stop();
			this.listener = null;
		}
		
		if (this.publisher != null) {
			this.publisher.stop();
			this.publisher = null;
		}
	}

}
