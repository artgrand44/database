package org.art.storage;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;

import org.art.db.common.DiscoveryHandler;
import org.art.db.server.Server;
import org.junit.jupiter.api.AfterEach;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BaseIntegrationTest extends BaseSQLTest {

	private static final Logger logger = LoggerFactory.getLogger(BaseIntegrationTest.class);
	
	private Process externalServer;
	
	private final String java = System.getProperty("java.home") + "/bin/java";
	
	@AfterEach
	public void afterEach() {
		
		super.afterEach();
		
		stopInternal();
		stopExternal();
		
		DiscoveryHandler.clear();
		
	}
	
	protected URI getLocalURI(String port) throws Exception {
		String host = InetAddress.getLocalHost().getHostAddress();
		
		return new URI(String.format("http://%s:%s", host, port));		
	}
	
	public void startExternal(String path) throws IOException {
		String classpath = System.getProperty("java.class.path");
		ProcessBuilder pb = new ProcessBuilder(java, "-cp", classpath, Server.class.getName(), path);
		
		pb.directory(new File("."));
		pb.inheritIO();
		
		this.externalServer = pb.start();
		logger.info("started external " + path);
	}
	
	public void startInternal(String path) {
		DiscoveryHandler.clear();
		Server.start(path, false);
		
	}
	

	public void stopExternal() {
		if (this.externalServer != null) {
			this.externalServer.destroyForcibly();
		}
	}
	
	public void stopInternal() {
		Server.stop();
		
	}
	
}
