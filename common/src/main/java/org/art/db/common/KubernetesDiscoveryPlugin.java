package org.art.db.common;

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
import io.fabric8.kubernetes.api.model.ServiceList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;

public class KubernetesDiscoveryPlugin implements DiscoveryPlugin {
	private static final Logger logger = LoggerFactory.getLogger(KubernetesDiscoveryPlugin.class);
			
	private final KubernetesClient client;
	KubernetesDiscoveryPlugin() {
		
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
			ServiceList svcList = this.client.services().withField("metadata.name", "helloworld").list();
			int shardCount = 1;

			if (!svcList.getItems().isEmpty()) {
				ObjectMeta metadata = svcList.getItems().get(0).getMetadata();

				logger.info("got meta " + metadata.getLabels());
				String shards = metadata.getLabels().get("shards");

				shardCount = shards == null || shards.isEmpty() ? 1 : Integer.parseInt(shards);

				if (shardCount <= 0 || shardCount > 500) {
					shardCount = 1;
				}	
			}

			logger.info("getting instances");
		
			
			EndpointsList endpointList = client.endpoints().withField("metadata.name", "helloworld").list();

			logger.info("endpoint count " + endpointList.getItems().size());

			for (Endpoints endpoints : endpointList.getItems()) {
				for (EndpointSubset subset : endpoints.getSubsets()) {
					if (!subset.getAddresses().isEmpty() && !subset.getPorts().isEmpty()) {
						EndpointAddress address = subset.getAddresses().get(0);
						EndpointPort port = subset.getPorts().get(0);
						URI uri = new URI(String.format("http://%s:%s", address.getIp(), port.getPort()));
						String shard = "Shard" + (uri.hashCode() % shardCount);

						result.put(uri, shard);
						
						logger.info("added " + uri + " " + shard);
					}
				}

				logger.info("instances " + result);
			}
		} catch (Exception exc) {
			logger.info("there was an error getting the instances " + exc.getLocalizedMessage());
			exc.printStackTrace();
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

}
