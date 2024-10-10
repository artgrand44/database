package org.art.db.common;

import java.net.URI;
import java.util.Collections;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DiscoveryPluginImpl implements DiscoveryPlugin {

	@SuppressWarnings("unused")
	private static final Logger logger = LoggerFactory.getLogger(DiscoveryPluginImpl.class);
			
	private HeartbeatPublisher publisher;
	
	private HeartbeatListener listener;
	
	private String localPort;
	private String localShard;
	private String localDatabase;
	private URI localInstance;
	
	public DiscoveryPluginImpl() {
		
	}
	
	@Override
	public void clear() {
		this.listener.clear();
	}
	
	@Override
	public Map<URI, String> getServerInstances() {
		return this.listener == null ? Collections.emptyMap() : this.listener.getInstances();
	}


	@Override
	public String getDatabase() {
		return this.localDatabase;
	}
	
	@Override
	public URI getLocalInstance() {
		return this.localInstance;
	}
	
	public String getLocalInstanceName() {
		return this.localInstance.getHost();
	}
	
	public String getLocalPort() {
		return this.localPort;
	}
	
	public String getLocalShard() {
		return this.localShard;
	}
	
	public void initialize(ConnectionInfo info, boolean isServer) { 
		this.listener = new HeartbeatListener(this.publisher, info.getDatabase(), info.toURI());
		this.localPort = info.getPort();
		this.localShard = info.getShard();
		this.localDatabase = info.getDatabase();
		this.localInstance = info.toURI();
		
		this.listener.intialize();
		this.listener.start();
		
		if (isServer) {
			this.publisher = new HeartbeatPublisher(info.getDatabase(), info.getShard(), info.toURI().getPort());
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
