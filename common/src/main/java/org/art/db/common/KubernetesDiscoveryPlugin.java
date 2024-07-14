package org.art.db.common;

import java.net.InetAddress;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;

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

public class KubernetesDiscoveryPlugin implements DiscoveryPlugin {
	private static final Logger logger = LoggerFactory.getLogger(KubernetesDiscoveryPlugin.class);
			
	private final KubernetesClient client;
	private String localPort;
	private String localShard;
	private String cluster;
	
	public KubernetesDiscoveryPlugin() {
		
		try {
			logger.info("Getting client");
			
			this.client = new KubernetesClientBuilder().build();
			logger.info("Got client");
		} catch (DBRuntimeException exc) {
			throw exc;
		} catch (Exception exc) {
			logger.info("Exception creating Kubernetes plugin " + exc.getMessage());
			exc.printStackTrace();
			throw new DBRuntimeException(exc);
		}
		
	}
	
	
	
	@Override
	public Map<URI, String> getInstances() {
		Map<URI, String> result = new HashMap<>();

		try {
			EndpointsList endpointList = client.endpoints().withField("metadata.name", "artdb").list();
			String localHostAddress =  InetAddress.getLocalHost().getHostAddress();
			
			for (Endpoints endpoints : endpointList.getItems()) {
				ObjectMeta meta = endpoints.getMetadata();
				Map<String, String> labels = meta.getLabels();
				String cluster = labels.get("cluster");
				String shard = labels.get("shard");
				
				logger.info("endpoint labels " + labels);
				
				if (!cluster.equalsIgnoreCase(this.cluster)) {
					logger.info("skip endpoint cluster = " + cluster + " local " + this.cluster);
				}
				
				for (EndpointSubset subset : endpoints.getSubsets()) {
					 
					if (!subset.getAddresses().isEmpty() && !subset.getPorts().isEmpty()) {
						EndpointAddress address = subset.getAddresses().get(0);
						EndpointPort port = subset.getPorts().get(0);
						URI uri = new URI(String.format("http://%s:%s", address.getIp(), port.getPort()));
						
						result.put(uri, shard);
						
						if (address.getIp().equals(localHostAddress)) {
							this.localPort = port.getPort().toString();
							this.localShard = shard;
							logger.info("set local port to " + this.localPort);
							logger.info("set local shard to " + this.localShard);
						}
					}
				}

				logger.info("instances " + result);
				logger.info("local host name" + InetAddress.getLocalHost().getHostName() + " hostaddress " +   InetAddress.getLocalHost().getHostAddress());
				logger.info("local  "  + InetAddress.getLocalHost());
			}
		} catch (Exception exc) {
			logger.info("there was an error getting the instances " + exc.getLocalizedMessage());
			exc.printStackTrace();
		}

		return result;
	}
	
	public String getLocalPort() { 
		return this.localPort;
	}
	
	public String getLocalShard() {
		return this.localShard;			
	}
	
	public void initialize(Configuration config, boolean isServer) {
		this.cluster = config.getCluster();
		
		logger.info("init set local cluster to " + this.cluster);
	}
	
	@Override
	public void start() {
		
	}

	@Override
	public void stop() {
		this.client.close();		
	}

}
