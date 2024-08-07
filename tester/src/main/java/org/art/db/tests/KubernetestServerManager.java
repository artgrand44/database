package org.art.db.tests;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.Charset;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;


public class KubernetestServerManager implements ServerManager {

	private static final Logger logger = LoggerFactory.getLogger(KubernetestServerManager.class);
	
	@Override
	public void startInternal(String profile) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void stopInternal() {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void startExternal(String profile) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void stopExternal() {
		// TODO Auto-generated method stub
		
	}
	
	private void start(String profile) {
		int dot = profile.lastIndexOf('.');
		String jsonPath = profile.substring(0,  dot) + ".json";
		
		try {
			String json = IOUtils.resourceToString(jsonPath, Charset.defaultCharset(), KubernetestServerManager.class.getClassLoader());
			ObjectMapper mapper = new ObjectMapper();
			
		} catch (Exception exc) {
			logger.info("Failed to start " + profile);
			exc.printStackTrace();
		}
	}

}
