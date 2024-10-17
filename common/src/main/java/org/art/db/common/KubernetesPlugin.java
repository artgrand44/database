package org.art.db.common;

import java.net.InetAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.KubernetesClientException;

public class KubernetesPlugin implements DiscoveryPlugin {
	private static final Logger logger = LoggerFactory.getLogger(KubernetesPlugin.class);
			
	private KubernetesClient client;
	private final String localPort = "8080";
	private final String localShard = System.getenv("org.art.db.shard");
	private String database;
	private URI localInstance;
	private AtomicReference<Map<URI, String>> serverInstances = new AtomicReference<Map<URI,String>>();
	private AtomicBoolean loading = new AtomicBoolean();
	private long ttl;
	
	private static String appName;
	
	
	public KubernetesPlugin(String database) {
		try {
			this.client = new KubernetesClientBuilder().build();
			this.database = database;
		} catch (DBRuntimeException exc) {
			throw exc;
		} catch (Exception exc) {
			exc.printStackTrace();
			throw new DBRuntimeException(exc);
		}		
	}
	
	public static String getAppName() {
		if (appName == null) {
			try {
				appName = "Unknown";
				
				String localHostAddress =  InetAddress.getLocalHost().getHostAddress();
				KubernetesClient client = new KubernetesClientBuilder().build();
				PodList podList =  client.pods().inNamespace("default").list();

				for (Pod pod : podList.getItems()) {
					String podIp = pod.getStatus().getPodIP();
					String phase = pod.getStatus().getPhase();					

					if (phase.equals("Running") && podIp != null && podIp.equals(localHostAddress)) {
						String[] tokens = pod.getMetadata().getName().split("-");

						if (tokens.length == 3) {
							appName = tokens[0];
						}
					} 
				}
			} catch (Exception exc) {
				logger.info("Error getting appname " + exc.getLocalizedMessage());
			}
		}
		
		return appName;

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
	
	
	@Override
	public Map<URI, String> getServerInstances() {
		
		if ((this.serverInstances.get() == null || System.currentTimeMillis() > this.ttl) && this.loading.compareAndSet(false, true)) {
			try {
				PodList podList =  client.pods().inNamespace("default").withLabel("database=" + this.database).list();
				
				Map<URI, String> result = new HashMap<>();
				String localHostAddress =  InetAddress.getLocalHost().getHostAddress();
				List<String> names = new ArrayList<>();
								
				for (Pod pod : podList.getItems()) {
					List<EnvVar> list = pod.getSpec().getContainers().get(0).getEnv();
					EnvVar shardVar = list.stream().filter(x -> x.getName().equals("org.art.db.shard")).findAny().orElse(null);
					String shard = shardVar.getValue();
					String podIp = pod.getStatus().getPodIP();
					String phase = pod.getStatus().getPhase();					
		    		
					if (phase.equals("Running") && podIp != null) {
						URI uri = new URI(String.format("http://%s:%s", podIp, "8080"));

						result.put(uri, shard);
						names.add(pod.getMetadata().getName());

						if (podIp.equals(localHostAddress)) {
							this.localInstance = uri;
						} 		
					} 
				}
						
				//logger.info("plugin found " + String.join(",", names));
				
				this.ttl = System.currentTimeMillis() + 10 * 1000;
				this.serverInstances.set(result);
			} catch (KubernetesClientException exc) {
				this.client = new KubernetesClientBuilder().build(); 
			} catch (Exception exc) {
				logger.info("there was an error getting the instances " + exc.getLocalizedMessage());				
			} finally {
				this.loading.set(false);
			}
		}

		return this.serverInstances.get();
	}
	
	
	public void initialize(ConnectionInfo info, boolean isServer) {
		
		try {
			
	        // Spin until we are able to find the instances and the local port, shard, and instance.
	        
	        Map<URI, String> instances = new HashMap<>();
	        
	        for (int idx = 0; idx < 1000; idx++) {
	        	
	        	instances = this.getServerInstances();
	        	
	        	if (instances.isEmpty() || (isServer && this.localInstance == null)) {
	        		Utils.sleepSafely(100);
	        	} else {
	        		break;
	        	}
	        }
	        
	        if (instances.isEmpty() || (isServer && this.localInstance == null)) {
	        	throw new DBRuntimeException("There was a timeout starting the server");
	        }	        			
		} catch (Exception exc) {
			exc.printStackTrace();
			throw new DBRuntimeException("There was an error finding the root " + exc.getMessage());
		} finally {
		}

	}
	
	public void poll() { 
		this.ttl = 0;
	}
	
	@Override
	public void start() {
		
	}
	
	@Override
	public void stop() {
		this.client.close();		
	}

	public static boolean isKubernetes() {
		return "kubernetes".equalsIgnoreCase(System.getenv("cloud")) || System.getenv("KUBERNETES_PORT") != null;
	}	

}
