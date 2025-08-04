package org.art.db.tests;

import java.io.File;

import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BaseTest {
	private static final Logger logger = LoggerFactory.getLogger(BaseTest.class);
	
	private static final File[] DATA_DIRECTORIES = new File[] {
		new File("./dbroot"),
		new File("./cluster2root"),
		new File("./atest"),
		new File("./secondroot"),
		new File("./duptest"),
		new File("./backup")
	};
	
	@BeforeEach
	public void beforeEach() throws Exception {
		if (System.getProperty("org.art.db.serviceURI") == null) {
			logger.info("Start test " + this.getClass().getSimpleName());
		}
		
		this.cleanDataDirectories();

	}
	
	@AfterEach
	public void afterEach() {
		this.cleanDataDirectories();
	}
	
	private void cleanDataDirectories() {
		for (File dir : DATA_DIRECTORIES) {
			if (dir.exists()) {
				try {
					FileUtils.cleanDirectory(dir);
				} catch (Exception exc) {
					logger.info("There was an error clearing " + dir.getAbsolutePath() + " " + exc.getMessage());
				}
			}
		}
	}
}
