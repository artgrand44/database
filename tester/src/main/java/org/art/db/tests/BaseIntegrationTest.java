package org.art.db.tests;

import java.io.IOException;
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
		stopPrimary();
		stopSecondary();
		
		DiscoveryHandler.clear();
		
	}
	
	protected URI getLocalURI(String port) throws Exception {
		String host = InetAddress.getLocalHost().getHostAddress();
		
		return new URI(String.format("http://%s:%s", host, port));		
	}
	
	public URI startSecondary(String path) throws IOException {
		return TestRunner.getServerManager().startSecondary(path);
	}
	
	public URI startPrimary(String path) {
		return TestRunner.getServerManager().startPrimary(path);
	}
	

	public void stopSecondary() {
		TestRunner.getServerManager().stopSecondary();
	}
	
	public void stopPrimary() {
		TestRunner.getServerManager().stopPrimary();
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
