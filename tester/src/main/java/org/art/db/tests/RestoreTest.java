package org.art.db.tests;

import java.io.File;
import java.net.URI;

import org.apache.commons.io.FileUtils;
import org.art.db.client.Client;
import org.art.db.common.DocumentSet;
import org.art.db.common.RestClient;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RestoreTest extends BaseIntegrationTest {
	private static final Logger logger = LoggerFactory.getLogger(RestoreTest.class);
	

	@Test
	public void test() {

		try  {
			File secondDir = new File("./secondroot");

			if (secondDir.exists()) {
				FileUtils.deleteDirectory(secondDir);
			}

			// Start both servers, wait until the are up, and then create the table
			// on both servers.
			
			TestRunner.getAdapter().startPrimary("./replication.properties");
			TestRunner.getAdapter().startSecondary("./secondaryrecovery.properties");

			Client primaryClient = TestRunner.getAdapter().getPrimaryClient("admin", "admin").batch(false);
			Client secondaryClient = TestRunner.getAdapter().getSecondaryClient("admin", "admin").batch(false);

			primaryClient.waitServerCount(2);
			secondaryClient.waitServerCount(2);

			Client client = TestRunner.getAdapter().getClusterClient("admin", "admin");
			
			String drop = "drop table if exists pounder";
			String create = "CREATE TABLE pounder (  ID int NOT NULL PRIMARY KEY, LastName varchar(255) NOT NULL default 'art', FirstName varchar(255), Age int)";
			String createIndex = "CREATE INDEX firstname ON pounder(FirstName)";
			String nameQuery = "select * from pounder where FirstName = ?";
			String idQuery = "select * from pounder where ID = ?";
			
			client.execute(drop);
			client.execute(create);
			client.execute(createIndex);
			
			// Take down the internal server then create documents until there are at least
			// 3 disk segments
			
			TestRunner.getAdapter().stopPrimary();
			
			logger.info("Stopped internal");
			
			for (int idx = 0; idx < 1000; idx++) {
				this.insert(secondaryClient, idx);

			}
			
			// Flush the external server then copy the index and the oldest segment.
			// This is the only way to insure that the index is flushed.
			
			logger.info("Flushing external");
			
			URI external = TestRunner.getAdapter().getSecondardURI();
			RestClient restClient = new RestClient();
			
			restClient.get(restClient.buildURI(external, "/db/close"));
			
						
			// Restart the internal server and wait until the recovery is complete
			
			logger.info("restarting");
			
			TestRunner.getAdapter().startPrimary("./replication.properties");
			
			primaryClient.waitServerCount(2);
			
			logger.info("recovery complete checking ");
			
			// Stop the internal server then verify that all of the documents and
			// index entries are in the external server	
			
			for (int idx = 0; idx < 1000; idx += 500) {
				int id = this.getId(idx);
				String name = this.getName(idx);
				
				DocumentSet ids = primaryClient.executeQuery(idQuery, id);
				DocumentSet names = primaryClient.executeQuery(nameQuery, name);
				
				if (ids.size() != 1) {
					logger.info("Missing id " + id);
					ids = primaryClient.executeQuery(idQuery, id);
					ids = secondaryClient.executeQuery(idQuery, id);					
				}
				
				Assertions.assertThat(ids.size() == 1).isTrue();
				Assertions.assertThat(ids.iterator().next().get("ID").equals(Long.valueOf(id))).isTrue();
				
				if (names.size() != 1) {
					logger.info("Bad names " + names.size() + " name = " + name + " id = " + id);
				}
				
				Assertions.assertThat(names.size() == 1).isTrue();
				Assertions.assertThat(ids.iterator().next().get("FirstName").equals(name)).isTrue();
			}
			
			logger.info("After check loop");

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
		
		client.execute(insert, getId(idx), getName(idx));
		
	}
	
}
