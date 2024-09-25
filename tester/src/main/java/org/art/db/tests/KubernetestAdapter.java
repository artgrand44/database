package org.art.db.tests;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.apache.commons.io.IOUtils;
import org.art.db.client.Client;
import org.art.db.common.DBRuntimeException;
import org.art.db.common.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.IntOrString;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.dsl.LogWatch;
import io.fabric8.kubernetes.client.dsl.PodResource;
import io.fabric8.kubernetes.client.utils.Serialization;

public class KubernetestAdapter extends TestAdapter {

	private static final Logger logger = LoggerFactory.getLogger(KubernetestAdapter.class);
	
	private KubernetesClient client = new KubernetesClientBuilder().build();
	
	private Server primary;
	
	private Server secondary;
	
	public Client getClusterClient(String user, String password) throws Exception {
		return new Client(new URI(String.format("database:/%s", this.primary.database)), user, password);
	}
	
	private String getLog(Server server) {
		try {
			PodList podList  = client.pods().inNamespace("default").withLabel("appname=" + server.name).list();

			for (Pod pod : podList.getItems()) { 
				URI uri = new URI(String.format("http://%s:%s", pod.getStatus().getPodIP(), "8080"));

				if (uri.equals(server.uri)) {
					PodResource res = client.pods().inNamespace("default").resource(pod);

					try (ByteArrayOutputStream bstream = new ByteArrayOutputStream(); PrintStream out = new PrintStream(bstream)){
						@SuppressWarnings("unused")
						LogWatch ignore = res.tailingLines(100000).watchLog(out);
						
						Utils.sleepSafely(2000);

						out.flush();
						bstream.flush();

						return "\n\nLog for " + server.name + "\n\n" + new String(bstream.toByteArray()) + "\n\n";
					}
				} 
			}
		} catch (Exception exc) {
			logger.info("There was an exception getting the log " + exc.getMessage());
		}
		
		return "";
	}
	
	public Client getPrimaryClient(String user, String password) throws Exception {
		return new Client(new URI(this.primary.uri.toString() + "/" + this.primary.database), user, password);
	}
	
	public URI getPrimaryURI() {
		return this.primary.uri;
	}
	private String getResourceAsString(String resource) throws Exception {
		try (InputStream stream = KubernetestAdapter.class.getClassLoader().getResourceAsStream(resource)) {
			return new String(IOUtils.toByteArray(stream));			
		}
	}
	
	public URI getSecondardURI() {
		return this.secondary.uri;
	}
	
	public Client getSecondaryClient(String user, String password) throws Exception {
		return new Client(new URI(this.secondary.uri.toString() + "/" + this.secondary.database), user, password);
	}
	
	public synchronized String getSecondaryLog() { 
		return this.secondary != null ? "no seondart" : this.getLog(this.secondary); 
	}
	
	private void setEnvironment(List<EnvVar> list, String name, String value) {
		for (EnvVar var : list) {
			if (var.getName().equals(name)) {
				var.setValue(value);
				return;
			}
		}
		
		list.add(new EnvVar(name, value, null));
	}
	
	private Server start(String profile) {
		try {
			
			// Read the yaml template then set the application name to the name of the profile
			
			
			String yaml = this.getResourceAsString("custom.yaml");
			Deployment deployment = Serialization.unmarshal(yaml, Deployment.class);
			
			String name = new File(profile).getName();
			int dot = name.lastIndexOf('.');
			String shortName = name.substring(0,  dot);
						
			deployment.getMetadata().setName(shortName);
			deployment.getSpec().getSelector().getMatchLabels().put("appname", shortName);
			deployment.getSpec().getTemplate().getMetadata().getLabels().put("appname", shortName);
						
			// Load the property file for the profile
			
			Properties props = new Properties();
						
			try (StringReader reader = new StringReader(this.getResourceAsString(new File(profile).getName()))) {
				props.load(reader);
			}
			
			List<EnvVar> list = deployment.getSpec().getTemplate().getSpec().getContainers().get(0).getEnv();
			
			for (Map.Entry<Object, Object> entry : props.entrySet()) {
				this.setEnvironment(list, (String)entry.getKey(), (String)entry.getValue());
			}			
			
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
			
			client.apps().deployments().inNamespace("default").resource(deployment).delete();
			client.services().inNamespace("default").resource(service).delete();
			
			logger.info("completed deletes");
			
			while (!client.pods().inNamespace("default").withLabel("appname=" + shortName).list().getItems().isEmpty()) {
				Utils.sleepSafely(1000);
			}
			
			logger.info("starting creates");
			
			this.client.apps().deployments().inNamespace("default").resource(deployment).create();
			this.client.services().inNamespace("default").resource(service).create();
		    
		    List<URI> uris = new ArrayList<>();
		    List<String> names = new ArrayList<>();
		    
		    for (int idx = 0; idx < 100; idx++) {
		    	PodList podList = client.pods().inNamespace("default").withLabel("appname=" + shortName).list();
		    	
		    	logger.info("total pods " + podList.getItems().size());
		    	
		    	for (Pod pod : podList.getItems()) {
		    		if (pod.getStatus().getPhase().equals("Running") &&  pod.getStatus().getPodIP() != null) {
		    			uris.add(new URI(String.format("http://%s:%s", pod.getStatus().getPodIP(), "8080")));
		    			
		    			names.add(pod.getMetadata().getName());
		    		} 
		    	}

		    	if (uris.isEmpty()) {
		    		Utils.sleepSafely(1000);
		    	} else {
		    		String database = deployment.getMetadata().getLabels().get("database");
					
		    		logger.info("adapter found " + String.join(",", names));
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
		
		logger.info("started primary " + this.primary.uri);
		return this.primary.uri;
	}

	@Override
	public synchronized  URI startSecondary(String profile) {
		if (this.primary != null && this.primary.profile.equals(profile)) {
			this.secondary = new Server(this.primary, this.primary.uris.get(1));
		} else {
			this.secondary = start(profile);
		}
		
		return this.secondary.uri;
	}


	private void stop(Server server) {
		client.services().inNamespace("default").resource(server.service).delete();
		client.apps().deployments().inNamespace("default").resource(server.deployment).delete();
	}


	@Override
	public void stopPrimary() {
		if (this.primary != null) {
			logger.info(this.getLog(this.primary));
			this.stop(this.primary);
			this.primary = null;
			
			if (this.primary == this.secondary) {
				this.secondary = null;
			}
		}

	}


	@Override
	public void stopSecondary() {
		if (this.secondary != null) {
			
			logger.info(this.getLog(this.secondary));
			this.stop(this.secondary);
			this.secondary = null;
		}		
	}

	record Server(String profile, String name, String database, Deployment deployment, Service service,  List<URI> uris, URI uri) {
		
		Server(Server from, URI uri) {
			this(from.profile, from.name, from.database, from.deployment, from.service, from.uris, uri);
		}
		
	}

}
