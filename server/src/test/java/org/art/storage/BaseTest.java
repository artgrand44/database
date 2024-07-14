package org.art.storage;

import java.io.File;

import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.fabric8.kubernetes.api.model.EndpointAddress;
import io.fabric8.kubernetes.api.model.EndpointPort;
import io.fabric8.kubernetes.api.model.EndpointSubset;
import io.fabric8.kubernetes.api.model.Endpoints;
import io.fabric8.kubernetes.api.model.EndpointsList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;

public class BaseTest {
	private static final Logger logger = LoggerFactory.getLogger(BaseTest.class);
	
	private static final File[] DATA_DIRECTORIES = new File[] {
		new File("./dbroot"),
		new File("./cluster2root"),
		new File("./atest"),
		new File("./secondroot"),
	};
	
	@BeforeEach
	public void beforeEach() throws Exception {
		logger.info("Start test " + this.getClass().getSimpleName());
		
		this.cleanDataDirectories();
		
		/*try (KubernetesClient client = new KubernetesClientBuilder().build()) {
			EndpointsList endpointList;
						
			endpointList = client.endpoints().withField("metadata.name", "helloworld").list();
			
			logger.info("count " + endpointList.getItems().size());
			
			for (Endpoints endpoints : endpointList.getItems()) {
				for (EndpointSubset subset : endpoints.getSubsets()) {
					if (!subset.getAddresses().isEmpty() && !subset.getPorts().isEmpty()) {
						EndpointAddress address = subset.getAddresses().get(0);
						EndpointPort port = subset.getPorts().get(0);
						
						logger.info(" by name ip " + address.getIp() + ":" + port.getPort() + " " + port);
					}
					
				}
				
			}
			
			
			
		} catch (Exception exc) {
			exc.printStackTrace();
		}*/
		
		logger.info("done");

	}
	
	@AfterEach
	public void afterEach() {
		this.cleanDataDirectories();
	}
	
	private void cleanDataDirectories() {
		for (File dir : DATA_DIRECTORIES) {
			if (dir.exists()) {
				try {
					FileUtils.cleanDirectory(dir);
				} catch (Exception exc) {
					logger.info("There was an error clearing " + dir.getAbsolutePath() + " " + exc.getMessage());
				}
			}
		}
	}
}
