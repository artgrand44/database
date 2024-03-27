package org.art.db.common;

import java.beans.PropertyChangeSupport;
import java.net.InetAddress;
import java.net.URI;
import java.util.ArrayList;
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
	
	private DiscoveryPlugin plugin = new PluginLoader().getPlugin(DiscoveryPlugin.class);
	
	
	private AtomicReference<MultiValueMap<String, URI>> instances = new AtomicReference<MultiValueMap<String, URI>>(new MultiValueMap<String, URI>());
	
	
	private AtomicReference<Set<InetAddress>> hosts = new  AtomicReference<>(new HashSet<>());
	
	private ConcurrentHashMap<URI, URI> recoveringInstances = new ConcurrentHashMap<>();
	
	private String port;
	
	private final String localShard;
	
	private final URI localInstance;
	
	private String cluster;
	
	private ScheduledFuture<?> poller;
	
	private final boolean isServer;
	
	protected AtomicReference<ShardFinder> shardFinder = new AtomicReference<>();
	
	private Map<String, AtomicLong> lastUsed = new ConcurrentHashMap<>();
	
	
	private final PropertyChangeSupport pcs = new PropertyChangeSupport(this);
	
	private static ConcurrentHashMap<Configuration, DiscoveryHandler> handlers = new ConcurrentHashMap<>();
	
	private static DiscoveryHandler serverInstance;
	
	
	public DiscoveryHandler(Configuration config, boolean isServer) throws Exception {
		this.localInstance = config.toURI();
		this.cluster = config.getCluster();
		this.localShard = config.getShard();
		this.port = config.getPort();
		this.isServer = isServer;
		
		if (isServer) {
			serverInstance = this;
		}
		
		this.plugin.initialize(this.localInstance, this.cluster, this.localShard, this.isServer);
		
		Thread.sleep(300);
		
		this.loadEnvironment();
		
		this.poller = SCHEDULER.scheduleAtFixedRate(this::loadEnvironment, 1, 20, TimeUnit.SECONDS);
			
		this.waitInstanceCount(1,  300);
		
	}
	
	public void addRecoveringInstance(URI uri) {
		this.recoveringInstances.put(uri,  uri);
	}
	
	public static void clear() {
		handlers.clear();
		serverInstance = null;
	}
	
	public static DiscoveryHandler getHandler(Configuration config) {
		return handlers.computeIfAbsent(config, name -> {
			try {
				return  new DiscoveryHandler(config, false);
			} catch (Exception exc) {
				throw new IllegalStateException(exc);
			}
		});
	}
	
	public URI getLocalInstance() {
		return this.localInstance;
	}
	
	public String getLocalPort() {
		return this.port;
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
	
	public String getlocalShard() {
		return this.localShard;
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
		return  this.instances.get().get(this.localShard).size();
	}
	
	/**
	 * Get the instances for the local service
	 * @return
	 */
	public List<URI> getLocalPeers() {
		
		MultiValueMap<String, URI> instances = this.instances.get();
		
		if (instances == null) {
			logger.info("no instances");
		} else if (instances.get(this.localShard) == null) {
			logger.info("np instances for service " + this.localShard + " in " + instances);
		}
		
		Set<URI> peers = new HashSet<>(instances.get(this.localShard));
		
		peers.remove(localInstance);
		peers.addAll(this.recoveringInstances.keySet());
		
		return new ArrayList<>(peers);
	
	}
	
	public URI getServiceAddressForKey(String key) {

		for (int retry = 0; retry < 50; retry++) {
			ShardFinder shardFinder;
			String shard = null;

			if ((shardFinder = this.shardFinder.get()) == null || (shard = shardFinder.getShard(key)) == null) {
				sleep(1000);
				this.loadEnvironment();
				continue;
			}
			
			List<URI> list = this.instances.get().get(shard);

			if (list == null || list.isEmpty()) {
				sleep(1000);
				this.plugin.clear();
				logger.info("*** clear plugin");
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
	public boolean hasServices() {
		return !this.instances.get().isEmpty();
	}
	
	public synchronized void loadEnvironment()  {
		try {
			MultiValueMap<String, URI> instances = new MultiValueMap<>();			
			Map<URI, String> map = this.plugin.getInstances();
			Set<InetAddress> hosts = new HashSet<>();
			ShardFinder oldFinder = this.shardFinder.get();
			
			for (Map.Entry<URI, String> entry : map.entrySet()) {
				URI uri = entry.getKey();
				String shard = entry.getValue();
				
				instances.add(shard, uri);
				hosts.add(InetAddress.getByName(uri.getHost()));
				this.recoveringInstances.remove(uri);
			}
			
			if (this.localInstance != null && !map.containsKey(this.localInstance)) {
				instances.add(this.localShard, this.localInstance);
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
			logger.error(exc.getMessage());
		}
	
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
		logger.info("Warmup completed on " + this.port);
		
		this.plugin.start();
	}
	
	public void stop() {
		this.poller.cancel(true);
		this.plugin.stop();
	}
	
	@Override
	public String toString() {
		return "DiscoveryHandler [port=" + port + ", instances="
				+ instances + ", shardMap=" + shardFinder + "]";
	}
	

	private static void sleep(long millis) {
		try {
			Thread.sleep(millis);
		} catch (Exception exc) {
		}
	}
	
	public void waitInstanceCount(int desiredCount, long timeout) throws Exception {
		
		long totalTime = 0;
		
		while (this.getInstanceCount(this.instances.get()) < desiredCount) {
			if (totalTime >= timeout) {
				throw new Exception("Unable to find services on " + this.port + " timeout " + timeout);
			}
			
			sleep(1000);
			this.loadEnvironment();
		}
	
	}
	
		
}
