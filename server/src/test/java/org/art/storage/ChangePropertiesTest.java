package org.art.storage;

import java.io.File;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.Charset;

import org.apache.commons.io.FileUtils;
import org.art.db.client.Client;
import org.art.db.common.ConnectionInfo;
import org.art.db.common.Document;
import org.art.db.common.DocumentSet;
import org.art.db.common.RestClient;
import org.art.db.common.Utils;
import org.art.db.storage.Environment;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ChangePropertiesTest extends BaseIntegrationTest {
	private static final Logger logger = LoggerFactory.getLogger(ChangePropertiesTest.class);
	
	
	
	@Test
	public void test() {
		
			try {
				URL  url = Environment.class.getClassLoader().getResource(".");
				File root = new File(url.toURI());
				File primaryFile = new File(root, "primary.properties");
				File secondaryFile = new File(root, "secondary.properties");

				FileUtils.write(primaryFile, "org.art.db.console=true\n", Charset.defaultCharset().name(), false);
				FileUtils.write(secondaryFile, "org.art.db.console=true\norg.art.db.port=8081\norg.art.db.dbroot=./secondroot\n", Charset.defaultCharset().name(), false);
				
				logger.info("before start = " + primaryFile.lastModified());
				startInternal(primaryFile.getAbsolutePath());
				startExternal(secondaryFile.getAbsolutePath());
				
				Client primaryClient = new Client(getLocalURI("8080"), "*", "admin", "admin");
				Client secondaryClient = new Client(getLocalURI("8081"), "*", "admin", "admin");
				
				primaryClient.waitServerCount(2);
				secondaryClient.waitServerCount(2);
				
				logger.info("before update = " + primaryFile.getAbsolutePath() + " " + primaryFile.lastModified());
				FileUtils.write(primaryFile, "org.art.db.console=false\n", Charset.defaultCharset().name(), false);
				logger.info("after update  = " + primaryFile.getAbsolutePath() + " "  + primaryFile.lastModified());
				
				Utils.sleepSafely(30 * 1000);
				
				logger.info("after sleep = " + primaryFile.getAbsolutePath() + " " + primaryFile.lastModified());
				
				String modified = FileUtils.readFileToString(secondaryFile, Charset.defaultCharset().name());
				
				if (!modified.contains("console=false")) {
					logger.info(modified);
				}
				
				Assertions.assertThat(modified.contains("console=false")).withFailMessage("Modified not changed").isTrue();
		} catch (AssertionFailedError err) {
			throw err;
		} catch(Throwable exc) {
			exc.printStackTrace();
			Assertions.fail(exc.getMessage());
		} 
	}

}

