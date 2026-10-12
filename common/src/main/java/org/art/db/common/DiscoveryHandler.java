package org.art.db.common;

import java.beans.PropertyChangeSupport;
import java.net.InetAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DiscoveryHandler {
	
	
	public static final String MODE_CHANGED = "mode_changed";
	public static final String SHARDS_CHANGED = "shards_changed";
	
	private static final Logger logger = LoggerFactory.getLogger(DiscoveryHandler.class);
	
	private static final ScheduledExecutorService SCHEDULER = Scheduler.createScheduler("Discovery", 1);
	
	private HeartbeatListener listener;
	
	private HeartbeatPublisher publisher;
	
	
	private AtomicReference<MultiValueMap<String, URI>> instances = new AtomicReference<MultiValueMap<String, URI>>(new MultiValueMap<String, URI>());
	
	
	private AtomicReference<Set<InetAddress>> hosts = new  AtomicReference<>(new HashSet<>());
	
	private String port;
	
	private final URI localInstance;
	
	private final String cluster;
	
	private final String localShard;
	
	private final AtomicInteger localHash = new AtomicInteger();
	
	private final ConnectionInfo config;
	
	private int referenceCount;
	
	
	public String getCluster() {
		return cluster;
	}

	private ScheduledFuture<?> poller;
		
	protected AtomicReference<ShardFinder> shardFinder = new AtomicReference<>();
	
	private Map<String, AtomicLong> lastUsed = new ConcurrentHashMap<>();
	
	
	private final PropertyChangeSupport pcs = new PropertyChangeSupport(this);
	
	private static ConcurrentHashMap<ConnectionInfo, DiscoveryHandler> handlers = new ConcurrentHashMap<>();
	
	private static DiscoveryHandler serverInstance;
	
	
	public DiscoveryHandler(ConnectionInfo config, boolean isServer)  {
		this.publisher = isServer ? new HeartbeatPublisher(config.getCluster(), config.getShard(), Integer.parseInt(config.getPort())) : null;
		this.listener = new HeartbeatListener(this.publisher, config.getCluster(), config.toURI());
		
		this.listener.intialize();
		this.listener.start();
		
		if (isServer) {
			this.publisher.initialize();
			this.listener.setPublisher(this.publisher);
			serverInstance = this;
		}	
		
		Utils.sleepSafely(300);
		
		this.port = config.getPort();
		this.cluster = config.getCluster();
		this.localShard = config.getShard();
		this.localInstance = config.toURI();
		this.config = config;
				
		this.loadEnvironment();
		
		this.poller = SCHEDULER.scheduleAtFixedRate(this::loadEnvironment, 1, 20, TimeUnit.SECONDS);
			
		try {
			this.waitInstanceCount(1,  300);
		} catch (Exception exc) {
			throw new IllegalStateException(exc);
		}
	}
		
	public static void clear() {
		for (DiscoveryHandler handler : handlers.values()) {
			handler.stop();
		}
		
		handlers.clear();
		serverInstance = null;
	}
	
	public void free() {
		synchronized (DiscoveryHandler.class) {
			if (--this.referenceCount == 0) {
				this.stop();
				handlers.remove(this.config);
			}
		}
	}
	
	public synchronized static DiscoveryHandler getHandler(ConnectionInfo info) {
		DiscoveryHandler handler = handlers.computeIfAbsent(info, _ -> new DiscoveryHandler(info, false));
		
		handler.referenceCount++;
		return handler;
	}
	
	
	public URI getLocalInstance() {
		return this.localInstance;
	}
	
	public String getLocalPort() {
		return this.port;
	}

	
	public static String getServerName() {
		return serverInstance == null ? "<none>" : serverInstance.getLocalInstance().toString();
	}
	
	public static DiscoveryHandler getServerInstance() {
		return serverInstance;
	}
	
	private int getInstanceCount(MultiValueMap<String, URI> instances) {
		int count = 0;
		
		for (List<URI> list : instances.values()) {
			count += list.size();
		}
		
		return count;
	}
	
	public MultiValueMap<String, URI> getInstances() {
		return this.instances.get();
	}
	
	public List<URI> getInstancesForShard(String shard) {
		return this.instances.get().get(shard);
	}
	
	public String getLocalShard() {
		String shard = this.localShard;
		
		if (shard == null || shard.isEmpty()) {
			new Exception().printStackTrace();
		}
		
		return shard;
	}
	
	
	public List<URI> getAllPeers() {
		MultiValueMap<String, URI> instances = this.instances.get();
		List<URI> peers = new ArrayList<>();
		
		for (List<URI> list : instances.values()) {
			peers.addAll(list);
		}
		
		peers.remove(localInstance);
		
		return peers;
	}
	
	public int getLocalInstanceCount() {
		return  this.instances.get().get(this.getLocalShard()).size();
	}
	
	/**
	 * Get the instances for the local service
	 * @return
	 */
	public List<URI> getLocalPeers() {
		
		for (int idx = 0; idx < 10; idx++) {
			MultiValueMap<String, URI> instances = this.instances.get();

			if (instances == null || instances.get(this.getLocalShard()) == null) {
				Utils.sleepSafely(1000);
			} else {
				Set<URI> peers = new HashSet<>(instances.get(this.getLocalShard()));

				peers.remove(this.localInstance);
			
				return new ArrayList<>(peers);
			}
		}
		
		return Collections.emptyList();
	}
	
	public URI getRandomPeer() {
		List<URI> peers = this.getLocalPeers();
		
		if (peers.isEmpty()) {
			return null;
		}
			
		AtomicLong counter = this.lastUsed.get(this.getLocalShard());
		int index = (int) Math.abs(counter.incrementAndGet() % peers.size());
		return peers.get(index);
	}
	
	public URI getServiceAddressForKey(String key) {

		for (int retry = 0; retry < 50; retry++) {
			ShardFinder shardFinder;
			String shard = null;

			if ((shardFinder = this.shardFinder.get()) == null || (shard = shardFinder.getShard(key)) == null) {
				Utils.sleepSafely(1000);
				this.loadEnvironment();
				continue;
			}
			
			List<URI> list = this.instances.get().get(shard);

			if (list == null || list.isEmpty()) {
				Utils.sleepSafely(1000);
				
				if (this.listener != null) {
					this.listener.clear();
				} 
				
				this.loadEnvironment();
				continue;
			} 

			AtomicLong counter = this.lastUsed.get(shard);
			int index = (int) Math.abs(counter.incrementAndGet() % list.size());
			return list.get(index);

		}

		throw new IllegalStateException("There are no shards");


	}

	public String getShard(String key) {
		return this.shardFinder.get().getShard(key);
	}
	
	public Set<String> getShards() {
		return this.instances.get().keySet();
	}
	
	/**
	 * This method is called when we recover the first instance of
	 * a brand new shard.  It determines which shard should be split
	 * in order to create the new shard and returns an instance of that shard.
	 * @param newShard
	 * @return
	 */
	public URI getSourceShard(String newShard) {
		for (int retry = 0; retry < 50; retry++) {
			ShardFinder shardFinder;
			
			if ((shardFinder = this.shardFinder.get()) == null || shardFinder.isEmpty()) {
				logger.info("sleep");
				Utils.sleepSafely(1000);
				this.loadEnvironment();
				continue;
			}
			
			ShardFinder clone = new ShardFinder(shardFinder);
			
			clone.removeShard(newShard);
			
			return this.getServiceAddressForKey(clone.getShard(newShard));
		}
		
		throw new DBRuntimeException("getSourceShard timed out");
	}
	
	public boolean isLocal(Key key) {
		return key.hashCode() % this.instances.get().keySet().size() == this.localHash.get();
	}
	
	public boolean hasServices() {
		return !this.instances.get().isEmpty();
	}
	
	public synchronized void loadEnvironment()  {
		try {
			MultiValueMap<String, URI> instances = new MultiValueMap<>();			
			Map<URI, String> map = this.listener == null ? Collections.emptyMap() : this.listener.getInstances();
			Set<InetAddress> hosts = new HashSet<>();
			ShardFinder oldFinder = this.shardFinder.get();
			
			for (Map.Entry<URI, String> entry : map.entrySet()) {
				URI uri = entry.getKey();
				String shard = entry.getValue();
				
				instances.add(shard, uri);
				hosts.add(InetAddress.getByName(uri.getHost()));
			}
			
			if (this.localInstance != null && !map.containsKey(this.localInstance)) {
				instances.add(this.getLocalShard(), this.localInstance);
				hosts.add(InetAddress.getByName(this.localInstance.getHost()));
			}		
		
			ShardFinder finder = new ShardFinder(instances.keySet());
			
			for (String shard : instances.keySet()) {
				if (!this.lastUsed.containsKey(shard)) {
					this.lastUsed.put(shard, new AtomicLong(0L));
				}
			}
	
			this.instances.set(instances);
			this.hosts.set(hosts);
			this.shardFinder.set(finder);
			this.localHash.set(this.localInstance == null ? 0 :this.localInstance.hashCode() % instances.keySet().size());
			
			if (!finder.equals(oldFinder)) {
				this.pcs.firePropertyChange(SHARDS_CHANGED, oldFinder, finder);
			}
		} catch (Exception exc) {
			exc.printStackTrace();
			logger.error(exc.getMessage());
		}
	
	}
	
	public void pausePublishing() {
		this.publisher.pause();
	}
	
	public void refresh() {
		this.loadEnvironment();
	}
	
	
	/**
	 * Tenporarily remove an instance until the next time we load the environment;
	 * @param instance
	 */
	public void removeInstance(URI instance) {
		MultiValueMap<String, URI> oldInstances = this.instances.get();
		MultiValueMap<String, URI> newInstances  = new MultiValueMap<String, URI>();
		
		for (Map.Entry<String, List<URI>> entry : oldInstances.entrySet()) {
			String shard = entry.getKey();
			List<URI> list = new ArrayList<>(entry.getValue());
			
			list.remove(instance);
			
			newInstances.put(shard, list);
		}
		
		if (this.listener != null) {
			this.listener.removeInstance(instance);
		} 
		
		this.instances.set(newInstances);
	}
	
	public void start() {
		if (this.publisher != null) {
			this.publisher.start();
		}
	}
	
	public void resumePublishing() {
		this.publisher.resume();
	}
	
	public void stop() {
		this.poller.cancel(true);
		
		if (this.listener != null) {
			this.listener.stop();
			this.listener = null;
		}
		
		if (this.publisher != null) {
			this.publisher.stop();
			this.publisher = null;
		}
	}
	
	@Override
	public String toString() {
		return "DiscoveryHandler [port=" + port +", localShard=" + getLocalShard() + ", instances="
				+ instances + ", shardMap=" + shardFinder + "]";
	}
	
	
	public void waitInstanceCount(int desiredCount, long timeout) throws Exception {
		
		long totalTime = 0;
		
		while (this.getInstanceCount(this.instances.get()) < desiredCount) {
			if (totalTime >= timeout) {
				throw new Exception("Unable to find services on " + this.port + " timeout " + timeout);
			}
			
			Utils.sleepSafely(Math.min(timeout, 1000));
			this.loadEnvironment();
		}
	
	}
	
}
