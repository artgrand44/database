package org.art.storage;

import java.net.InetAddress;
import java.net.URI;

import org.art.db.client.Client;
import org.art.db.tests.TestAdapter;
import org.art.db.tests.TestRunner;
import org.assertj.core.api.Assertions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class IntegrationTests extends BaseIntegrationTest {

	private static final Logger logger = LoggerFactory.getLogger(IntegrationTests.class);
	
	//@Test
	public void test() {
		String results = TestRunner.run(new Adapter());
		
		logger.info("results\n" + results);
		
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
	
	
		
	public class Adapter extends TestAdapter {
		
		@Override
		public URI startPrimary(String profile) {
			startInternal(profile);
			
			return getURI("8080");
		}
		
		public void stopPrimary() {
			
		}
		
		@Override
		public URI startSecondary(String profile) {
			startExternal(profile);
			return getURI("8081");
		}
		
		@Override
		public void stopSecondary() {
			
		}
		@Override
		public Client getClusterClient(String user, String password) throws Exception {
			return new Client(new URI(getLocalURI("8080").toString() + "/cluster"), user, password);
		}
		
		@Override
		public Client getPrimaryClient(String user, String password) throws Exception {
			return new Client(new URI(getLocalURI("8080").toString() + "/cluster"), user, password);
		}
		
		@Override
		public Client getSecondaryClient(String user, String password) throws Exception {
			return new Client(new URI(getLocalURI("8081").toString() + "/cluster"), user, password);
		}
		
		@Override
		public URI getPrimaryURI() {
			return getURI("8080");
		}
		
		
		@Override
		public URI getSecondardURI() {
			return getURI("8081");
		}
	}
}
