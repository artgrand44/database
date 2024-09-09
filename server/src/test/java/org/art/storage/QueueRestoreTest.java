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

public class QueueRestoreTest extends BaseIntegrationTest {
	
	private static final Logger logger = LoggerFactory.getLogger(QueueRestoreTest.class);
	
	@Test
	public void test() {

		try  {
			File secondDir = new File("./secondroot");

			if (secondDir.exists()) {
				FileUtils.deleteDirectory(secondDir);
			}

			super.startExternal("./secondaryrecovery.properties");
			
			

			Client external = new Client(getLocalURI("8081"), "*", "admin", "admin").batch(false);
			external.waitServerCount(1);

			String create = "CREATE QUEUE TABLE restore (  ID int NOT NULL PRIMARY KEY, LastName varchar(255) NOT NULL default 'art', FirstName varchar(255), Age int)";
			String createIndex = "CREATE INDEX firstname ON restore(FirstName)";
			String idQuery = "select * from restore where ID = ?";
			RestClient rest = new RestClient();
			
			logger.info("send create");
			external.execute(create);
			external.execute(createIndex);
			
			// Take down the internal server then create documents until there are at least
			// 3 disk segments
			
			//super.stopInternal();
			
			logger.info("Stopped internal");
			
			File internalDir = new File("/Users/artgrand/repros/database/server/dbroot/SYS.SYS.restore");
			File externalDir = new File("/Users/artgrand/repros/database/server/secondroot/SYS.SYS.restore");
			File[] files = new File[0];
			int count = 0;
			
			for (int idx = 0; ; idx++) {
				this.insert(external, idx);
				
				if (idx % 10 == 0) {
					logger.info("send flush");
					rest.get(rest.buildURI(this.getLocalURI("8081"), "/db/flush?table=SYS.SYS.restore"));
					logger.info("after send flush");
				}
				count++;
								
				files = externalDir.listFiles((dir, name) -> name.endsWith(".log"));
				
				if (files != null && files.length != 0) {
					break;
				}
			}
			
			// Flush the external server then copy the index and the oldest segment.
			// This is the only way to insure that the index is flushed.
			
			logger.info("Flushing external " + count);
			
			URI externalURI = this.getLocalURI("8081");
			RestClient restClient = new RestClient();
			
			restClient.get(restClient.buildURI(externalURI, "/db/close"));
			
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
			
			Client internalClient = new Client(getLocalURI("8080"), "*", "admin", "admin");
			
			
			internalClient.waitServerCount(2);
			
			logger.info("recovery complete checking " + count);
			
			// Stop the internal server then verify that all of the documents and
			// index entries are in the external server	
			
			for (int idx = 0; idx < count; idx += 500) {
				int id = this.getId(idx);
				String name = this.getName(idx);
				
				DocumentSet ids = internalClient.executeQuery(idQuery, id);
				
				if (ids.size() != 1) {
					logger.info("Missing id " + id);
					ids = internalClient.executeQuery(idQuery, id);
					ids = external.executeQuery(idQuery, id);					
				}
				
				Assertions.assertThat(ids.size() == 1).isTrue();
				Assertions.assertThat(ids.iterator().next().get("ID").equals(Long.valueOf(id))).isTrue();
				
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
		String insert = "insert into restore (ID, FirstName, LastName, Age) values (?, ?, 'grand', '1')";
		
		client.execute(insert, getId(idx), getName(idx));
		
	}
	
}
