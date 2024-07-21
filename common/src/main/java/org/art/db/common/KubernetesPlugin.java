package org.art.db.common;

import static io.fabric8.kubernetes.client.utils.Utils.generateId;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.fabric8.kubernetes.api.model.EndpointAddress;
import io.fabric8.kubernetes.api.model.EndpointPort;
import io.fabric8.kubernetes.api.model.EndpointSubset;
import io.fabric8.kubernetes.api.model.Endpoints;
import io.fabric8.kubernetes.api.model.EndpointsList;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.extended.leaderelection.LeaderCallbacks;
import io.fabric8.kubernetes.client.extended.leaderelection.LeaderElectionConfig;
import io.fabric8.kubernetes.client.extended.leaderelection.LeaderElectionConfigBuilder;
import io.fabric8.kubernetes.client.extended.leaderelection.LeaderElector;
import io.fabric8.kubernetes.client.extended.leaderelection.resourcelock.LeaseLock;

public class KubernetesPlugin implements DiscoveryPlugin {
	private static final Logger logger = LoggerFactory.getLogger(KubernetesPlugin.class);
			
	private static final KubernetesPlugin instance = new KubernetesPlugin();
	private final KubernetesClient client;
	private String localPort;
	private String localShard;
	private String database;
	private URI localInstance;
	private AtomicReference<Map<URI, String>> serverInstances = new AtomicReference<Map<URI,String>>();
	private AtomicBoolean loading = new AtomicBoolean();
	private long ttl;
	
	private volatile String root;
	
	private KubernetesPlugin() {
		try {
			this.client = new KubernetesClientBuilder().build();
			this.database = System.getenv("database");
		} catch (DBRuntimeException exc) {
			throw exc;
		} catch (Exception exc) {
			exc.printStackTrace();
			throw new DBRuntimeException(exc);
		}		
	}
	
	@Override
	public String getDatabase() {
		return this.database;
	}
	public String getLocalPort() { 
		return this.localPort;
	}
	
	@Override
	public URI getLocalInstance() {
		return this.localInstance;
	}
	public String getLocalShard() {
		return this.localShard;			
	}
	
	public String getRoot() {
		return root;
	}
	
	@Override
	public Map<URI, String> getServerInstances() {
		
		if ((this.serverInstances.get() == null || System.currentTimeMillis() > this.ttl) && this.loading.compareAndSet(false, true)) {
			try {
				logger.info("starting services scan " + this.database);
										
				String localHostAddress =  InetAddress.getLocalHost().getHostAddress();
				EndpointsList endpointList = client.endpoints().withField("metadata.name", this.database).list();
				Map<URI, String> result = new HashMap<>();
				
				for (Endpoints endpoints : endpointList.getItems()) {
					ObjectMeta meta = endpoints.getMetadata();
					Map<String, String> labels = meta.getLabels();
					String database = labels.get("database");
					String shard = labels.get("shard");

					
					logger.info("labels " + labels + " shard = " + shard + " database " + database + " this.database " + this.database);
					
					if (database.equalsIgnoreCase(this.database)) {
						for (EndpointSubset subset : endpoints.getSubsets()) {					 
							if (!subset.getAddresses().isEmpty() && !subset.getPorts().isEmpty()) {
								EndpointAddress address = subset.getAddresses().get(0);
								EndpointPort port = subset.getPorts().get(0);
								URI uri = new URI(String.format("http://%s:%s", address.getIp(), port.getPort()));

								result.put(uri, shard);

								if (address.getIp().equals(localHostAddress)) {
									this.localPort = port.getPort().toString();
									this.localShard = shard;
									this.localInstance = uri;
								}
							}
						}
					} else {
						logger.info("skip " + meta);
					}
				}

				logger.info("instances " + result);
				logger.info("local host name " + InetAddress.getLocalHost().getHostName() + " hostaddress " +   InetAddress.getLocalHost().getHostAddress());
				logger.info("local host IP "  + InetAddress.getLocalHost());
				logger.info("local shard "  + this.localShard);
				logger.info("local instance "  + this.localInstance);

				this.ttl = System.currentTimeMillis() + 10 * 1000;
				this.serverInstances.set(result);
			} catch (Exception exc) {
				logger.info("there was an error getting the instances " + exc.getLocalizedMessage());
				exc.printStackTrace();
			} finally {
				this.loading.set(false);
			}
		}

		return this.serverInstances.get();
	}
	
	public void initialize(ConnectionInfo info, boolean isServer) {
		
		try {
			logger.info("*** entry to init");
			
			// Lock the cluster, call initializeRoot, and release the lock.
			// initializeRoot will set this.root to a new directory or to
			// an existing directory for an instance that has crashed.
			
			LeaderElectionConfig leaderConfig = new LeaderElectionConfigBuilder()
					                                	.withName(this.database)
					                                	.withLeaseDuration(Duration.ofMinutes(2))
					                                	.withLock(new LeaseLock("default", this.database, generateId().toString()))
					                                	.withReleaseOnCancel()
					                                	.withLeaderCallbacks(new LeaderCallbacks(this::initializeRoot, () -> {}, x-> {}))
					                                	.withRenewDeadline(Duration.ofSeconds(10L))
					                                    .withRetryPeriod(Duration.ofSeconds(2L))
					                                	.build();
			
			CompletableFuture<?> future = new LeaderElector(this.client, leaderConfig, Scheduler.DEFAULT).start();
			
			while (this.root == null) {
				Thread.sleep(5000);
			}
			
			logger.info("after spin " + this.root);
	        future.cancel(true);

	        // Spin until we are able to find the instances and the local port, shard, and instance.
	        
	        Map<URI, String> instances = new HashMap<>();
	        
	        for (int idx = 0; idx < 1000; idx++) {
	        	
	        	instances = this.getServerInstances();
	        	
	        	if (instances.isEmpty()) {
	        		Utils.sleepSafely(100);
	        	} else {
	        		break;
	        	}
	        }
	        
	        if (instances.isEmpty()) {
	        	throw new DBRuntimeException("There was a timeout starting the server");
	        }	        
		} catch (Exception exc) {
			exc.printStackTrace();
			throw new DBRuntimeException("There was an error finding the root " + exc.getMessage());
		} finally {
		}

	}
	
	private void initializeRoot() {
		
		// Open the cluster.dat file in the directory.  This file is a csv file mapping
		// host names into their root directories.

		try {
			File mountDir = new File("./mount");
			logger.info("********* initializing root");
			
			mountDir.mkdir();

			File rootList = new File(mountDir, this.database + ".dat");
			
			Map<String, String> roots = this.readCSV(rootList);
			
			// Iterator over the entries in cluster.dat looking for an abandoned 
			// root directory.  If you find one then update the cluster.dat file,
			// set this.root, and return.

			String localHost = InetAddress.getLocalHost().getHostAddress();
			Set<String> hosts = new HashSet<>();

			for (URI uri : this.getServerInstances().keySet()) {
				hosts.add(uri.getHost());
			}

			for (Map.Entry<String, String> entry : roots.entrySet()) {
				String uri = entry.getKey();
				String root = entry.getValue();

				if (!hosts.contains(entry.getKey())) {
					roots.remove(uri);
					roots.put(localHost, root);
					this.writeCSV(roots, rootList);
					this.root = new File(mountDir, entry.getValue()).getAbsolutePath();

					logger.info("Using old " + this.root);
					return;
				}
			}

			// Create a new root directory using the host name

			roots.put(localHost, localHost);
			this.writeCSV(roots, rootList);

			File newRoot = new File(mountDir, localHost);

			logger.info("using new " + newRoot.getAbsolutePath());

			newRoot.mkdir();
			this.root = newRoot.getAbsolutePath();
		} catch (Exception exc) {
			exc.printStackTrace();
			throw new DBRuntimeException("There was an error finding the root " + exc.getMessage());
		} finally {

		}

	}
	
	private Map<String, String> readCSV(File file) throws IOException {
		Map<String, String> result = new HashMap<>();

		if (file.exists()) {
			try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
				String line;

				while ((line = reader.readLine()) != null) {
					String[] tokens = line.trim().split(",");
					result.put(tokens[0], tokens[1]);
				}
			}
		}

		return result;
	}
	
	@Override
	public void start() {
		
	}
	
	@Override
	public void stop() {
		this.client.close();		
	}
	
	private void writeCSV(Map<String, String> map, File file) throws IOException {
		logger.info("writing to " + file.getAbsolutePath());
		try (PrintWriter writer = new PrintWriter(new FileWriter(file))) {
			for (Map.Entry<String, String> entry : map.entrySet()) {
				writer.println(entry.getKey() + "," + entry.getValue());
			}
		}
	}

	public static KubernetesPlugin getInstance() {
		return instance;
	}

	public static boolean isKubernetes() {
		return "kubernetes".equalsIgnoreCase(System.getenv("cloud"));
	}

	

}
