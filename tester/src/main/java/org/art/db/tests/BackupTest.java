package org.art.db.tests;

import java.io.File;

import org.apache.commons.io.FileUtils;
import org.art.db.client.Client;
import org.art.db.common.ConnectionInfo;
import org.art.db.common.RestClient;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BackupTest extends BaseIntegrationTest {
	private static final Logger logger = LoggerFactory.getLogger(BackupTest.class);
	
	private static int NITEMS = 100;
	
	@Test
	public void test() {
		try {
			File root = new File("./backup");
			
			if (root.exists()) {
				FileUtils.cleanDirectory(root);
			}
			
			TestAdapter.getInstance().startPrimary("./backup.properties");
			
			Client client = TestAdapter.getInstance().getClusterClient("admin", "admin").batch(false);
			String drop = "drop table if exists pounder";
			String create = "CREATE TABLE pounder (  ID int NOT NULL PRIMARY KEY, LastName varchar(255) NOT NULL default 'art', FirstName varchar(255), Age int)";
			String createIndex = "CREATE INDEX firstname ON pounder(FirstName)";
			
			client.execute(drop);
			client.execute(create);
			client.execute(createIndex);
			
			for (int idx = 0; idx < NITEMS; idx++) {
				this.insert(client, idx);
				
				if (idx % 1000 == 0) {
					logger.info("Inserted " + idx);
				}
			}
			
			logger.info("inserts complete");
			
			ConnectionInfo primary = new ConnectionInfo(TestAdapter.getInstance().getPrimaryURI().getHost(), "8080", "averlongshardname", "db2");
			RestClient restClient = new RestClient();
			
			restClient.get(restClient.buildURI(primary.toURI(), "/db/backup"));
		} catch (AssertionFailedError err) {
			throw err;
		} catch(Throwable exc) {
			exc.printStackTrace();
			Assertions.fail(exc.getMessage());
		} 
		
	}
	
	private String getName(int idx) {
		return "Name" + idx;
	}
	
	private int getId(int idx) {
		return idx;
	}
	
	private void insert(Client client, int idx) throws Exception {
		String insert = "insert into pounder (ID, FirstName, LastName, Age) values (?, ?, 'grand', '1')";
		
		try {
			client.execute(insert, getId(idx), getName(idx));	
		} catch (Exception exc) {
			logger.info("exception on " + idx);
		}
	}

}
