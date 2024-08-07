package org.art.storage;

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
}
