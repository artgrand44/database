package org.art.storage;

import java.io.File;
import java.net.URI;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.apache.commons.io.FileUtils;
import org.art.db.client.Client;
import org.art.db.common.DocumentSet;
import org.art.db.common.RestClient;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RecoveryTest extends BaseClientTest {
	private static final Logger logger = LoggerFactory.getLogger(RecoveryTest.class);
	

	@Test
	public void test() {

		try  {
			File secondDir = new File("./secondroot");

			if (secondDir.exists()) {
				FileUtils.deleteDirectory(secondDir);
			}

			// Start both servers, wait until the are up, and then create the table
			// on both servers.
			
			super.startInternal("./replication.properties");
			super.startExternal("./secondaryrecovery.properties");

			Client primaryClient = new Client(getLocalURI("8080"), "*", "admin", "admin");
			Client secondaryClient = new Client(getLocalURI("8081"), "*", "admin", "admin");

			primaryClient.waitServerCount(2);
			secondaryClient.waitServerCount(2);

			Client client = new Client(getLocalURI("8080"), "*", "admin", "admin");
			String create = "CREATE TABLE pounder (  ID int NOT NULL PRIMARY KEY, LastName varchar(255) NOT NULL default 'art', FirstName varchar(255), Age int)";
			String createIndex = "CREATE INDEX firstname ON pounder(FirstName)";
			String nameQuery = "select * from pounder where FirstName = ?";
			String idQuery = "select * from pounder where ID = ?";
			
			client.execute(create);
			client.execute(createIndex);
			
			// Take down the internal server then create documents until there are at least
			// 3 disk segments
			
			super.stopInternal();
			
			logger.info("Stopped internal");
			
			File internalDir = new File("/Users/artgrand/repros/database/server/dbroot/SYS.SYS.pounder");
			File externalDir = new File("/Users/artgrand/repros/database/server/secondroot/SYS.SYS.pounder");
			File[] files = new File[0];
			int count = 0;
			
			for (int idx = 0; ; idx++) {
				this.insert(secondaryClient, idx);
				
				count++;
								
				if (idx % 1000 == 0) {
					//logger.info("Inserted " + idx);
				}
				
				files = externalDir.listFiles((dir, name) -> name.endsWith(".seg"));
				
				if (files != null && files.length > 3) {
					break;
				}
			}
			
			// Flush the external server then copy the index and the oldest segment.
			// This is the only way to insure that the index is flushed.
			
			logger.info("Flushing external");
			
			URI external = this.getLocalURI("8081");
			RestClient restClient = new RestClient();
			
			restClient.get(restClient.buildURI(external, "/db/close"));
			
			logger.info("Flushing external");
						
			if (files != null && files.length != 0) {
				List<File> list = Arrays.asList(files);
				Collections.sort(list, (a, b) -> a.getName().compareTo(b.getName()));

				FileUtils.copyFile(list.get(0), new File(internalDir, list.get(0).getName()));

				File indexFile = new File(externalDir, "firstname.idx");
				
				FileUtils.copyFile(indexFile, new File(internalDir,  "firstname.idx"));				
			}		
			
			// Restart the internal server and wait until the recovery is complete
			
			logger.info("restarting");
			
			super.startInternal("./replication.properties");
			
			primaryClient.waitServerCount(2);
			
			logger.info("recovery complete checking " + count);
			
			// Stop the internal server then verify that all of the documents and
			// index entries are in the external server	
			
			for (int idx = 0; idx < Math.min(1000, count); idx++) {
				int id = this.getId(idx);
				String name = this.getName(idx);
				
				if (idx % 100 == 0) {
					logger.info("check " + idx);
				}
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
