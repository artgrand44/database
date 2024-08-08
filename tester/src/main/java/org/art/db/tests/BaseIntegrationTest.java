package org.art.db.tests;

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
	
}
