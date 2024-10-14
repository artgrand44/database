package org.art.db.common;

import java.net.InetAddress;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;

public class ApplicationContext {
	private static final Logger logger = LoggerFactory.getLogger(KubernetesPlugin.class);
	
	
	private final String instanceName;
	private final String appName;
	
	private static final ApplicationContext instance = new ApplicationContext();
	
	private ApplicationContext() {
		
		String instanceName = "Unknown";
		String appName = System.getenv("org.art.db.client");
		
		if (StringUtils.isEmpty(appName)) {
			appName = System.getProperty("org.art.db.client", "Unknown");
		}
				
		try {
			String localHostAddress =  InetAddress.getLocalHost().getHostAddress();
			
			instanceName = localHostAddress;
			
			KubernetesClient client = new KubernetesClientBuilder().build();
			PodList podList =  client.pods().inNamespace("default").list();
			
			for (Pod pod : podList.getItems()) {
				String podIp = pod.getStatus().getPodIP();
				String phase = pod.getStatus().getPhase();					
	    		
				if (phase.equals("Running") && podIp != null && podIp.equals(localHostAddress)) {
					instanceName = pod.getMetadata().getName();
					
					String[] tokens = instanceName.split("-");
					
					if (tokens.length == 3) {
						appName = tokens[0];
					}
				} 
			}

		} catch (Exception exc) {
			logger.info("Error initializing context " + exc.getLocalizedMessage());
		}
		
		
		this.appName = appName;
		this.instanceName = instanceName;
	}

	public static ApplicationContext getInstance() {
		return instance;
	}

	public String getInstanceName() {
		return instanceName;
	}

	public String getAppName() {
		return appName;
	}

	@Override
	public String toString() {
		return "ApplicationContext [instanceName=" + instanceName + ", appName=" + appName + "]";
	}
	
	
}
