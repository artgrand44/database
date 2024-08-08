package org.art.db.tests;

import java.io.File;
import java.net.URI;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.commons.io.IOUtils;
import org.art.db.client.Client;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.fabric8.kubernetes.api.model.IntOrString;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;


public class KubernetestServerManager implements TestAdapter {

	private static final Logger logger = LoggerFactory.getLogger(KubernetestServerManager.class);
	
	record Server(String profile, String name, List<URI> uris, URI uri) {
		
	}
	
	private Server primary;
	
	private Server secondary;
	
	public Client getClusterClient(String user, String password) throws Exception {
		return new Client(new URI(String.format("database:/%s", this.primary.name)), user, password);
	}
	public Client getPrimaryClient(String user, String password) throws Exception {
		return new Client(this.primary.uri, user, password);
	}
	
	public Client getSecondaryClient(String user, String password) throws Exception {
		return new Client(this.secondary.uri, user, password);
	}
	
	private Server start(String profile) {
		String name = new File(profile).getName();
		int dot = name.lastIndexOf('.');
		String shortName = name.substring(0,  dot);
		String jsonPath = "./" + shortName + ".json";
		
		try {
			String json = IOUtils.resourceToString(jsonPath, Charset.defaultCharset(), KubernetestServerManager.class.getClassLoader());
			ObjectMapper mapper = new ObjectMapper();
			Deployment deployment = mapper.readValue(json, Deployment.class);
			KubernetesClient client = new KubernetesClientBuilder().build();;
			
			deployment = client.apps().deployments().inNamespace("default").resource(deployment).create();
	        
			Service service = new ServiceBuilder()
		            .withNewMetadata().withName("nginx-svc").addToLabels("app", "nginx").endMetadata()
		            .withNewSpec()
		            .addNewPort()
		            .withName("http")
		            .withPort(80)
		            .withTargetPort(new IntOrString(8080))
		            .endPort()
		            .withSelector(Collections.singletonMap("app", "name"))
		            .withType("NodePort")
		            .endSpec()
		            .build();
			
		    client.services().inNamespace("default").resource(service).create();
		    
		    List<URI> uris = new ArrayList<>();
		    
		    PodList podList = client.pods().withLabel("app=" + shortName).list();
			
			for (Pod pod : podList.getItems()) {
				uris.add(new URI(String.format("http://%s:%s", pod.getStatus().getPodIP(), "8080")));			
			}
			
			return new Server(profile, shortName, uris, uris.get(0));
		} catch (Exception exc) {
			logger.info("Failed to start " + profile);
			throw new IllegalStateException(exc);
		}
	}


	@Override
	public URI startPrimary(String profile) {
		this.primary = this.start(profile);
		
		return this.primary.uri;
	}


	@Override
	public void stopPrimary() {
		if (this.primary != null) {
			KubernetesClient client = new KubernetesClientBuilder().build();

			client.services().withLabel("app=" + this.primary.name).delete();

			if (this.primary == this.secondary) {
				this.secondary = null;
			}

			this.primary = null;
		}

	}


	@Override
	public URI startSecondary(String profile) {
		if (this.primary != null && this.primary.profile.equals(profile)) {
			this.secondary = new Server(this.primary.profile, this.primary.name, this.primary.uris, this.primary.uris.get(1));
		} else {
			this.secondary = start(profile);
		}
		
		return this.secondary.uri;
	}

	@Override
	public void stopSecondary() {
		if (this.secondary != null) {
			KubernetesClient client = new KubernetesClientBuilder().build();

			client.services().withLabel("app=" + this.secondary.name).delete();

			if (this.primary == this.secondary) {
				this.primary = null;
			}

			this.secondary = null;
		}		
	}

}
