package org.art.storage;

import java.net.InetAddress;
import java.net.URI;

import org.art.db.client.Client;
import org.art.db.tests.ServerManager;
import org.art.db.tests.TestRunner;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class IntegrationTests extends BaseIntegrationTest implements ServerManager{

	private static final Logger logger = LoggerFactory.getLogger(IntegrationTests.class);
	
	@Test
	public void test() {
		String results = TestRunner.run(this);
		
		logger.info("results");
		
		Assertions.assertThat(results.contains("Total Errors: 0")).isTrue();
	}

	private URI getURI(String port) {
		try {
			String host = InetAddress.getLocalHost().getHostAddress();

			return new URI(String.format("http://%s:%s", host, port));
		} catch (Exception exc) {
			throw new IllegalStateException(exc);
		}
	}
	
	@Override
	public URI startPrimary(String profile) {
		super.startInternal(profile);
		
		return getURI("8080");
	}

	@Override
	public void stopPrimary() {
		super.stopInternal();
		
	}

	@Override
	public URI startSecondary(String profile) {
		super.startExternal(profile);
		return getURI("8081");
		
	}

	@Override
	public void stopSecondary() {
		super.stopExternal();
	}

	@Override
	public Client getClusterClient(String user, String password) throws Exception {
		return new Client(this.getLocalURI("8080"), user, password);
	}

	@Override
	public Client getPrimaryClient(String user, String password) throws Exception {
		return new Client(this.getLocalURI("8080"), user, password);
	}

	@Override
	public Client getSecondaryClient(String user, String password) throws Exception {
		return new Client(this.getLocalURI("8081"), user, password);
	}
}
