package org.art.db.common;

import java.net.InetAddress;

import org.apache.commons.lang3.StringUtils;

public class ApplicationContext {
	
	private final String instanceName;
	private final String appName;
	
	private static final ApplicationContext instance = new ApplicationContext();
	
	private ApplicationContext() {
		
		String instanceName = null;
		String appName = System.getenv("org.art.db.client");
		
		try {
			instanceName = InetAddress.getLocalHost().getHostAddress();
		} catch (Exception exc) {
			appName = "Unknown";
		}
		
		if (StringUtils.isEmpty(appName)) {
			appName = System.getProperty("org.art.db.client");
		}
				
		if (appName == null) {
			appName = KubernetesPlugin.isKubernetes() ? KubernetesPlugin.getAppName() : "Unknown";
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
