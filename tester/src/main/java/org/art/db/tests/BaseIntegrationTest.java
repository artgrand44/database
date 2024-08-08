package org.art.db.tests;

import java.net.InetAddress;
import java.net.URI;

import org.art.db.client.Client;
import org.art.db.common.DiscoveryHandler;
import org.junit.jupiter.api.AfterEach;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BaseIntegrationTest  {

	@SuppressWarnings("unused")
	private static final Logger logger = LoggerFactory.getLogger(BaseIntegrationTest.class);
	

	@AfterEach
	public void afterEach() {
		TestRunner.getAdapter().stopPrimary();
		TestRunner.getAdapter().stopSecondary();
		
		DiscoveryHandler.clear();
		
	}
	
	protected URI getLocalURI(String port) throws Exception {
		String host = InetAddress.getLocalHost().getHostAddress();
		
		return new URI(String.format("http://%s:%s", host, port));		
	}
	
	public Client getClient() throws Exception {
		return this.getClient("cluster", "admin", "admin");
	}
	
	public Client getClient(String cluster, String user, String password) throws Exception {
		String uri = System.getProperty("org.art.db.serviceURI");
		
		if (uri == null) {
			return new Client(new URI("database:/" + cluster), user, password);
		} else {
			return new Client(new URI(uri.toString() + "/" + cluster), user, password);
		}
	}
}
