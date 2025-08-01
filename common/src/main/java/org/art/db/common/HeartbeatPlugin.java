package org.art.db.common;

import java.net.URI;
import java.util.Collections;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HeartbeatPlugin implements DiscoveryPlugin {

	@SuppressWarnings("unused")
	private static final Logger logger = LoggerFactory.getLogger(HeartbeatPlugin.class);
			
	private HeartbeatPublisher publisher;
	
	private HeartbeatListener listener;
	
	public HeartbeatPlugin() {
		
	}
	
	@Override
	public void clear() {
		if (this.listener != null) {
			this.listener.clear();
		} 
	}
	
	@Override
	public Map<URI, String> getServerInstances() {
		return this.listener == null ? Collections.emptyMap() : this.listener.getInstances();
	}
	
	public void initialize(ConnectionInfo info, boolean isServer) { 
		this.listener = new HeartbeatListener(this.publisher, info.getCluster(), info.toURI());
		
		this.listener.intialize();
		this.listener.start();
		
		if (isServer) {
			this.publisher = new HeartbeatPublisher(info.getCluster(), info.getShard(), info.toURI().getPort());
			this.publisher.initialize();
			this.listener.setPublisher(this.publisher);
		}	
	}
	
	public void pausePublishing() { 
		if (this.publisher != null) {
			this.publisher.setPaused(false);
		}
	}
	
	
	@Override
	public void removeInstance(URI instance) {
		if (this.listener != null) {
			this.listener.removeInstance(instance);
		} 
	}
	
	public void resumePublishing() { 
		if (this.publisher != null) {
			this.publisher.setPaused(false);
		}
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
