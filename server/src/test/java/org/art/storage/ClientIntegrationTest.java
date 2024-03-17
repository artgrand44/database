package org.art.storage;

import org.art.db.common.Configuration;
import org.art.server.Server;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class ClientIntegrationTest extends BaseClientTest {
	
	@Test
	public void test() {
		Server.start("./application.properties");
		
		try {
			Configuration.getInstance().load("./application.properties");
			Configuration.getInstance().setProperty("org.art.db.user", "admin");
			Configuration.getInstance().setProperty("org.art.db.password", "admin");
			
			this.doTest(Configuration.getInstance());
		} catch (Exception exc) {
			Assertions.fail(exc.getMessage());
		}
	}
}
