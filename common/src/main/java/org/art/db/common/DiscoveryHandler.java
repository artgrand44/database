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
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DiscoveryHandler {
	
	
	public static final String MODE_CHANGED = "mode_changed";
	public static final String SHARDS_CHANGED = "shards_changed";
	
	private static final Logger logger = LoggerFactory.getLogger(DiscoveryHandler.class);
	
	private static final ScheduledExecutorService SCHEDULER = Scheduler.createScheduler("Discovery", 1);
	
	private final DiscoveryPlugin plugin;
	
	
	private AtomicReference<MultiValueMap<String, URI>> instances = new AtomicReference<MultiValueMap<String, URI>>(new MultiValueMap<String, URI>());
	
	
	private AtomicReference<Set<InetAddress>> hosts = new  AtomicReference<>(new HashSet<>());
	
	private String port;
	
	private final URI localInstance;
	
	private final String localDatabase;
	
	public String getLocalDatabase() {
		return localDatabase;
	}

	private ScheduledFuture<?> poller;
		
	protected AtomicReference<ShardFinder> shardFinder = new AtomicReference<>();
	
	private Map<String, AtomicLong> lastUsed = new ConcurrentHashMap<>();
	
	
	private final PropertyChangeSupport pcs = new PropertyChangeSupport(this);
	
	private static ConcurrentHashMap<ConnectionInfo, DiscoveryHandler> handlers = new ConcurrentHashMap<>();
	
	private static DiscoveryHandler serverInstance;
	
	
	public DiscoveryHandler(ConnectionInfo config, boolean isServer) throws Exception {
		
		this.plugin = new KubernetesPlugin(config.getDatabase());
			
		this.plugin.initialize(config, isServer);
		
		Utils.sleepSafely(300);
		
		this.port = this.plugin.getLocalPort();
		this.localDatabase = this.plugin.getDatabase();
		this.localInstance = this.plugin.getLocalInstance();
			
		if (isServer) {
			serverInstance = this;
		}
				
		this.loadEnvironment();
		
		this.poller = SCHEDULER.scheduleAtFixedRate(this::loadEnvironment, 1, 20, TimeUnit.SECONDS);
			
		this.waitInstanceCount(1,  300);
		
	}
	

	
	public static void clear() {
		for (DiscoveryHandler handler : handlers.values()) {
			handler.stop();
		}
		
		handlers.clear();
		serverInstance = null;
	}
	
	public synchronized static DiscoveryHandler getHandler(ConnectionInfo info) {
		return handlers.computeIfAbsent(info, name -> {
			try {
				return  new DiscoveryHandler(info, false);
			} catch (Exception exc) {
				throw new IllegalStateException(exc);
			}
		});
	}
	
	public URI getLocalInstance() {
		return this.localInstance;
	}
	
	public String getLocalPort() {
		return this.plugin.getLocalPort();
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
		String shard = this.plugin.getLocalShard();
		
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
				this.plugin.clear();
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
	
	public boolean hasServices() {
		return !this.instances.get().isEmpty();
	}
	
	public synchronized void loadEnvironment()  {
		try {
			MultiValueMap<String, URI> instances = new MultiValueMap<>();			
			Map<URI, String> map = this.plugin.getServerInstances();
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
			
			if (!finder.equals(oldFinder)) {
				this.pcs.firePropertyChange(SHARDS_CHANGED, oldFinder, finder);
			}
		} catch (Exception exc) {
			exc.printStackTrace();
			logger.error(exc.getMessage());
		}
	
	}
	
	public void refresh() {
		this.plugin.poll();
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
		
		this.plugin.removeInstance(instance);
		this.instances.set(newInstances);
	}
	
	public void start() {
		logger.info("Warmup completed");
		
		this.plugin.start();
	}
	
	public void stop() {
		this.poller.cancel(true);
		this.plugin.stop();
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
				throw new Exception("Unable to find services on " + this.plugin.getLocalPort() + " timeout " + timeout);
			}
			
			Utils.sleepSafely(Math.min(timeout, 1000));
			this.plugin.poll();
			this.loadEnvironment();
		}
	
	}
	
	static class SingletonDiscoveryPlugin implements DiscoveryPlugin {
		private final Map<URI, String> instances;
		private final URI localURI;
		
		SingletonDiscoveryPlugin(URI uri) {
			this.instances = Map.of(uri, "shard1");
			this.localURI = uri;
		}
		
		@Override
		public Map<URI, String> getServerInstances() {
			return this.instances;
		}

		@Override
		public void start() {		
		}

		@Override
		public void stop() {
		}

		@Override
		public String getLocalPort() {
			return "8080";
		}
		
		public String getLocalShard() {
			return "shard1";			
		}

		@Override
		public String getDatabase() {
			return "none";
		}

		@Override
		public URI getLocalInstance() {
			return this.localURI;
		}

		public String getLocalInstanceName() {
			return this.localURI.getHost();
		}
		
		@Override
		public void initialize(ConnectionInfo config, boolean isServer) {
		}
	}
		
}
