package org.art.db.common;

import java.io.File;
import java.net.InetAddress;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
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
	
	private final String root;
	
	private final File ROOT_DIR = new File("/data/files");
	
	private KubernetesPlugin() {
		try {
			this.client = new KubernetesClientBuilder().build();
			this.database = System.getenv("database");
			this.root = ROOT_DIR.getAbsolutePath();
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
				EndpointsList endpointList = client.endpoints().withLabel("database=" + this.database).list();
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
								for (int idx = 0; idx < subset.getAddresses().size(); idx++) {
									EndpointAddress address = subset.getAddresses().get(idx);
									
									for (int jdx = 0; jdx < subset.getPorts().size(); jdx++) {
										EndpointPort port = subset.getPorts().get(jdx);
										URI uri = new URI(String.format("http://%s:%s", address.getIp(), port.getPort()));

										result.put(uri, shard);
										
										if (address.getIp().equals(localHostAddress)) {
											this.localPort = port.getPort().toString();
											this.localShard = shard;
											this.localInstance = uri;
										}
									}
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
						
	        // Spin until we are able to find the instances and the local port, shard, and instance.
	        
	        Map<URI, String> instances = new HashMap<>();
	        
	        for (int idx = 0; idx < 1000; idx++) {
	        	
	        	instances = this.getServerInstances();
	        	
	        	if (instances.isEmpty() || this.localInstance == null) {
	        		Utils.sleepSafely(100);
	        	} else {
	        		break;
	        	}
	        }
	        
	        if (instances.isEmpty() || this.localInstance == null) {
	        	throw new DBRuntimeException("There was a timeout starting the server");
	        }	        			
		} catch (Exception exc) {
			exc.printStackTrace();
			throw new DBRuntimeException("There was an error finding the root " + exc.getMessage());
		} finally {
		}

	}
	
		
	@Override
	public void start() {
		
	}
	
	@Override
	public void stop() {
		this.client.close();		
	}
	
	public static KubernetesPlugin getInstance() {
		return instance;
	}

	public static boolean isKubernetes() {
		return "kubernetes".equalsIgnoreCase(System.getenv("cloud"));
	}	

}
