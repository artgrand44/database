package org.art.db.tests;

import org.art.db.client.Client;
import org.art.db.client.dao.DAOCompiler;
import org.art.db.common.DiscoveryHandler;
import org.art.db.common.Router;
import org.junit.jupiter.api.AfterEach;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class BaseIntegrationTest  {

	@SuppressWarnings("unused")
	private static final Logger logger = LoggerFactory.getLogger(BaseIntegrationTest.class);
	

	@AfterEach
	public void afterEach() {
		TestAdapter.getInstance().stopPrimary();
		TestAdapter.getInstance().stopSecondary();
		
		DiscoveryHandler.clear();
		Router.clear();
		Client.clear();
		DAOCompiler.getInstance().clear();
		
	}
	
}
