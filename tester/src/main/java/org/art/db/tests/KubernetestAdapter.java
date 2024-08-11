package org.art.db.tests;

import java.io.File;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.art.db.client.Client;
import org.art.db.common.DBRuntimeException;
import org.art.db.common.Utils;
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


public class KubernetestAdapter implements TestAdapter {

	private static final Logger logger = LoggerFactory.getLogger(KubernetestAdapter.class);
	
	record Server(String profile, String name, String database, Deployment deployment, Service service, List<URI> uris, URI uri) {
		
		Server(Server from, URI uri) {
			this(from.profile, from.name, from.database, from.deployment, from.service, from.uris, uri);
		}
		
	}
	
	private KubernetesClient client = new KubernetesClientBuilder().build();
	
	private Server primary;
	
	private Server secondary;
	
	public Client getClusterClient(String user, String password) throws Exception {
		return new Client(new URI(String.format("database:/%s", this.primary.database)), user, password);
	}
	public Client getPrimaryClient(String user, String password) throws Exception {
		return new Client(new URI(this.primary.uri.toString() + "/" + this.primary.database), user, password);
	}
	
	public URI getPrimaryURI() {
		return this.primary.uri;
	}
	
	public Client getSecondaryClient(String user, String password) throws Exception {
		return new Client(new URI(this.secondary.uri.toString() + "/" + this.secondary.database), user, password);
	}
	
	public URI getSecondardURI() {
		return this.secondary.uri;
	}
	
	private void stop(Server server) {
		//client.services().inNamespace("default").resource(server.service).delete();
		//client.apps().deployments().inNamespace("default").resource(server.deployment).delete();
	}
	
	private Server start(String profile) {
		String name = new File(profile).getName();
		int dot = name.lastIndexOf('.');
		String shortName = name.substring(0,  dot);
		String jsonPath = shortName + ".json";
		
		//File json = new File("/Users/artgrand/repros/database/tester/src/main/resources/queuepounder.json");
		File json = new File(new File("."), jsonPath);
		
		try {
			ObjectMapper mapper = new ObjectMapper();
			Deployment deployment = mapper.readValue(json, Deployment.class);
			String database = deployment.getMetadata().getLabels().get("database");
			String shard = deployment.getMetadata().getLabels().get("shard");
			
			client.apps().deployments().inNamespace("default").resource(deployment).delete();
			
			deployment = this.client.apps().deployments().inNamespace("default").resource(deployment).create();
	        
			Service service = new ServiceBuilder()
		            .withNewMetadata().withName(shortName).addToLabels("app", shortName).endMetadata()
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
			
			client.services().inNamespace("default").resource(service).delete();
			client.services().inNamespace("default").resource(service).create();
		    
		    List<URI> uris = new ArrayList<>();
		    
		    for (int idx = 0; idx < 100; idx++) {
		    	PodList podList = client.pods().inNamespace("default").withLabel("appname=" + shortName).list();
		    	
		    	logger.info("total pods " + podList.getItems().size());
		    	
		    	for (Pod pod : podList.getItems()) {
		    		if (pod.getStatus().getPhase().equals("Running") &&  pod.getStatus().getPodIP() != null) {
		    			uris.add(new URI(String.format("http://%s:%s", pod.getStatus().getPodIP(), "8080")));
		    		} 
		    	}

		    	if (uris.isEmpty()) {
		    		Utils.sleepSafely(1000);
		    	} else {
		    		return new Server(profile, shortName, database, deployment, service, uris, uris.get(0));
		    	}
		    }
		    
		    throw new DBRuntimeException("Unable to start service");
		} catch (Exception exc) {
			logger.info("Failed to start " + profile);
			exc.printStackTrace();
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
			this.stop(this.primary);
			this.primary = null;
			
			if (this.primary == this.secondary) {
				this.secondary = null;
			}
		}

	}


	@Override
	public URI startSecondary(String profile) {
		if (this.primary != null && this.primary.profile.equals(profile)) {
			logger.info("Resusing primary");
			this.secondary = new Server(this.primary, this.primary.uris.get(1));
		} else {
			logger.info("creating separate");
			this.secondary = start(profile);
			logger.info("new started");
		}
		
		return this.secondary.uri;
	}

	@Override
	public void stopSecondary() {
		if (this.secondary != null) {
			this.stop(this.secondary);
			this.secondary = null;
		}		
	}

}
