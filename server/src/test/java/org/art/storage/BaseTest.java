package org.art.storage;

import java.io.File;

import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BaseTest {
	private static final Logger logger = LoggerFactory.getLogger(BaseTest.class);
	
	@BeforeEach
	public void beforeEach() throws Exception {
		logger.info("Start test " + this.getClass().getSimpleName());
		
		System.setProperty("org.art.db.dbroot", "./dbroot");
		
		File dbRoot = new File("./dbroot");
		File cluster2Root = new File("./cluster2root");
		
		if (dbRoot.exists()) {
			FileUtils.cleanDirectory(dbRoot);	
		}
		
		if (cluster2Root.exists()) {
			FileUtils.cleanDirectory(cluster2Root);
		}
	}
	
	@AfterEach
	public void afterEach() {
		//StorageManager.getInstance().stop();
	}
}
