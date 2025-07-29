package org.art.db.tests;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.net.URI;
import java.time.Duration;
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

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.ConfigMapEnvSource;
import io.fabric8.kubernetes.api.model.EnvFromSource;
import io.fabric8.kubernetes.api.model.EnvFromSourceBuilder;
import io.fabric8.kubernetes.api.model.IntOrString;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodAntiAffinity;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.StatefulSet;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.dsl.LogWatch;
import io.fabric8.kubernetes.client.dsl.NonDeletingOperation;
import io.fabric8.kubernetes.client.dsl.PodResource;
import io.fabric8.kubernetes.client.utils.Serialization;

public class KubernetestAdapter extends TestAdapter {

	private static final Logger logger = LoggerFactory.getLogger(KubernetestAdapter.class);
	
	private KubernetesClient client = new KubernetesClientBuilder().build();
	
	private Server primary;
	
	private Server secondary;
	
	private String testName;
	
	public Client getClusterClient(String user, String password) throws Exception {
		return new Client(new URI(String.format("database:/%s", this.primary.database)), user, password);
	}
	
	public Client getClusterClient(String user, String password, int cacheSize, Duration cacheDuration) throws Exception {
		return new Client(new URI(String.format("database:/%s", this.primary.database)), user, password, cacheSize, cacheDuration);
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
	
	public void killAll() {
		try {
			for (Deployment deployment : client.apps().deployments().inNamespace("default").list().getItems()) {
				if (deployment.getMetadata().getName().startsWith("test-")) {
					client.apps().deployments().inNamespace("default").resource(deployment).delete();
				} 
			}

			for (Service service : client.services().inNamespace("default").list().getItems()) {
				if (service.getMetadata().getName().startsWith("test-")) {
					client.services().inNamespace("default").resource(service).delete();
				}
			}
		} catch (Exception exc) {
			logger.info("kill exception " + exc.getMessage());
		}
	}
	
	private Server start(String profile, String suffix) {
		try {
			logger.info("start profile " + profile);
			
			// Read the yaml template then set the application name to the name of the profile
			
			String setYaml = this.getResourceAsString("defaultset.yaml").replaceAll("defaultclusterdata", this.testName + suffix);
			StatefulSet set = Serialization.unmarshal(setYaml, StatefulSet.class);
			
			logger.info("Setting claim to " + this.testName + suffix);
			
			String name = new File(profile).getName();
			int dot = name.lastIndexOf('.');
			String shortName = name.substring(0,  dot);
						
			set.getMetadata().setName("test-" + shortName);
			set.getSpec().getSelector().getMatchLabels().put("appname", shortName);
			set.getSpec().getTemplate().getMetadata().getLabels().put("appname", shortName);
			
			PodAntiAffinity anti = set.getSpec().getTemplate().getSpec().getAffinity().getPodAntiAffinity();
			
			anti.getRequiredDuringSchedulingIgnoredDuringExecution().get(0).getLabelSelector().getMatchExpressions().getFirst().setValues(Collections.singletonList(shortName));
			
			// Load or build the config map
			
			ConfigMap configMap;
			
			if (profile.endsWith(".yaml")) {
				String yaml = this.getResourceAsString(new File(profile).getName());
				configMap = Serialization.unmarshal(yaml, ConfigMap.class);
			} else {
				Properties props = new Properties();
				List<String> pairs = new ArrayList<>();

				try (StringReader reader = new StringReader(this.getResourceAsString(new File(profile).getName()))) {
					props.load(reader);
				}

				props.forEach((x, y) -> pairs.add(x + "=" + y));

				configMap = new ConfigMapBuilder()
								.withData(Map.of("db.properties", String.join("\n", pairs)))
								.withMetadata(new ObjectMetaBuilder().withName("defaultcluster").build())
								.build();
			}
			
			client.configMaps().inNamespace("default").resource(configMap).unlock().createOr(NonDeletingOperation::update);
			
			// Find the database from the config 
			
			String[] pairs = configMap.getData().get("db.properties").split("\n");
			String database = "defaultcluster";
			
			for (String pair : pairs) {
				String[] tokens = pair.split("=");
				
				if (tokens[0].equals("org.art.db.cluster")) {
					database = tokens[1];
					logger.info("found db from config " + database);
					break;
				}
			}
			
			logger.info("database = " + database);
			
			EnvFromSource source = new EnvFromSourceBuilder()
					                   .withConfigMapRef(new ConfigMapEnvSource(shortName, true))
					                   .build();
			
			set.getSpec().getTemplate().getSpec().getContainers().get(0).setEnvFrom(Collections.singletonList(source));
			
			Service service = new ServiceBuilder()
		            .withNewMetadata().withName("test-" + shortName).addToLabels("app", shortName).endMetadata()
		            .withNewSpec()
		            .addNewPort()
		            .withName("http")
		            .withPort(80)
		            .withTargetPort(new IntOrString(8080))
		            .endPort()
		            .withSelector(Collections.singletonMap("app", "name"))
		            .withType("LoadBalancer")
		            .endSpec()
		            .build();
			
			client.apps().statefulSets().inNamespace("default").resource(set).delete();
			client.services().inNamespace("default").resource(service).delete();
			
			while (!client.pods().inNamespace("default").withLabel("appname=" + shortName).list().getItems().isEmpty()) {
				Utils.sleepSafely(1000);
			}
			
			this.client.apps().statefulSets().inNamespace("default").resource(set).create();
			this.client.services().inNamespace("default").resource(service).create();
		    
		    List<URI> uris = new ArrayList<>();
		    List<String> names = new ArrayList<>();
		    
		    for (int idx = 0; idx < 100; idx++) {
		    	PodList podList = client.pods().inNamespace("default").withLabel("appname=" + shortName).list();
		    	
		    	for (Pod pod : podList.getItems()) {
		    		if (pod.getStatus().getPhase().equals("Running") &&  pod.getStatus().getPodIP() != null) {
		    			uris.add(new URI(String.format("http://%s:%s", pod.getStatus().getPodIP(), "8080")));
		    			
		    			names.add(pod.getMetadata().getName());
		    		} 
		    	}

		    	if (uris.isEmpty()) {
		    		Utils.sleepSafely(1000);
		    	} else {
		    		return new Server(profile, shortName, database, set, service, uris, uris.get(0));
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
		this.primary = this.start(profile, "primary");
		
		logger.info("started primary " + this.primary.uri);
		return this.primary.uri;
	}

	@Override
	public synchronized  URI startSecondary(String profile) {
		if (this.primary != null && this.primary.profile.equals(profile)) {
			logger.info("primary and secondary have same profiles");
			this.secondary = new Server(this.primary, this.primary.uris.get(1));
		} else {
			this.secondary = start(profile, "secondary");
			logger.info("started secondary " + this.secondary.uri);
		}
		
		return this.secondary.uri;
	}

	public void startTest(String name) {
		this.testName = name;
	}

	private void stop(Server server) {
		client.services().inNamespace("default").resource(server.service).delete();
		client.apps().statefulSets().inNamespace("default").resource(server.deployment).delete();
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

	record Server(String profile, String name, String database, StatefulSet deployment, Service service,  List<URI> uris, URI uri) {
		
		Server(Server from, URI uri) {
			this(from.profile, from.name, from.database, from.deployment, from.service, from.uris, uri);
		}
		
	}

}
