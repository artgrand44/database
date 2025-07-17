package org.art.storage;

import java.io.File;
import java.net.InetAddress;
import java.net.URI;

import org.art.db.client.Client;
import org.art.db.common.DiscoveryHandler;
import org.art.db.common.Utils;
import org.art.db.server.Server;
import org.art.db.storage.RestoreManager;
import org.junit.jupiter.api.AfterEach;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BaseIntegrationTest extends BaseSQLTest {

	private static final Logger logger = LoggerFactory.getLogger(BaseIntegrationTest.class);
	
	private Process externalServer;
	private static boolean kunernetes;
	
	private final String java = System.getProperty("java.home") + "/bin/java";
	
	@AfterEach
	public void afterEach() {
		stopInternal();
		stopExternal();
		
		DiscoveryHandler.clear();
		
		super.afterEach();
		
		
	}
	
	protected URI getLocalURI(String port) throws Exception {
		String host = InetAddress.getLocalHost().getHostAddress();
		
		return new URI(String.format("http://%s:%s", host, port));		
	}
	
	public static boolean isKunernetes() {
		return kunernetes;
	}

	public static void setKunernetes(boolean kunernetes) {
		BaseIntegrationTest.kunernetes = kunernetes;
	}

	public void startExternal(String path)  {
		try {
			String classpath = System.getProperty("java.class.path");
			ProcessBuilder pb = new ProcessBuilder(java, "-cp", classpath, Server.class.getName(), path);

			pb.directory(new File("."));
			pb.inheritIO();

			this.externalServer = pb.start();
			logger.info("started external " + path);
		} catch (Exception exc) {
			throw new IllegalStateException(exc);
		}
	}
	
	public void startInternal(String path) {
		if (!kunernetes) {
			DiscoveryHandler.clear();
			Server.start(path);	
			
			while (!RestoreManager.getInstance().isRecovered()) {
				Utils.sleepSafely(1000);
			}
		}
	}
	

	public void stopExternal() {
		if (this.externalServer != null) {
			this.externalServer.destroy();
		}
	}
	
	public void stopInternal() {
		Server.stop();
		
	}
	
	public Client getClient() throws Exception {
		return this.getClient("defaultcluster", "admin", "admin");
	}
	
	public Client getClient(String cluster, String user, String password) throws Exception {
		String uri = System.getProperty("org.art.db.serviceURI");
		
		if (uri == null) {
			return new Client(new URI("database:/" + cluster), user, password);
		} else {
			return new Client(new URI(uri.toString() + "/" + cluster), user, password);
		}
	}
	
	public Client getPrimaryClient(String user, String password) throws Exception {
		return new Client(new URI(getLocalURI("8080").toString() + "/defaultcluster"), user, password);
	}
	
	public Client getSecondaryClient(String user, String password) throws Exception {
		return new Client(new URI(getLocalURI("8081").toString() + "/defaultcluster"), user, password);
	}
}
