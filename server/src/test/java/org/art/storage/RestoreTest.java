package org.art.storage;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.apache.commons.io.FileUtils;
import org.art.db.client.Client;
import org.art.db.common.DocumentSet;
import org.art.db.common.RestClient;
import org.art.db.common.Utils;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RestoreTest extends BaseIntegrationTest {
	private static final Logger logger = LoggerFactory.getLogger(RestoreTest.class);
	
	private volatile int extraCount;
	private volatile boolean needExtra = true;

	
	@Test
	public void test() {

		try  {
			File secondDir = new File("./secondroot");

			if (secondDir.exists()) {
				FileUtils.deleteDirectory(secondDir);
			}

			// Start the external server 
			
			super.startExternal("./secondaryrecovery.properties");

			Client external = new Client(getLocalURI("8081"), "*", "admin", "admin").batch(false);

			external.waitServerCount(1);

			String drop = "drop table if exists pounder";
			String create = "CREATE TABLE pounder (  ID int NOT NULL PRIMARY KEY, LastName varchar(255) NOT NULL default 'art', FirstName varchar(255), Age int)";
			String createIndex = "CREATE INDEX firstname ON pounder(FirstName)";
			RestClient restClient = new RestClient();
			
			external.execute(drop);
			external.execute(create);
			external.execute(createIndex);
			
			
			// Create the table in the external server
					
			// Insert documents into the external server until it has at least
			// three disk segments, then flush the external server to disk.
			
			File externalDir = new File("./secondroot/SYS.SYS.pounder");
			File[] segments = new File[0];
			int count = 0;
			
			logger.info("starting loop");
			
			for (int idx = 0; ; idx++) {
				this.insert(external, idx);
				
				if (idx % 10 == 0) {
					logger.info("send flush");
					restClient.get(restClient.buildURI(this.getLocalURI("8081"), "/db/flush?table=SYS.SYS.pounder"));
					logger.info("after send flush");
				}
				count++;
								
				segments = externalDir.listFiles((dir, name) -> name.endsWith(".seg"));
				
				if (segments != null && segments.length > 1) {
					break;
				}
				
				if (idx % 10000 == 0) {
					logger.info("idx " + idx);
				}
			}
			
			restClient.get(restClient.buildURI(this.getLocalURI("8081"), "/db/close"));
			
			logger.info("after external flush");
						
			
			File internalDir = new File("./dbroot/SYS.SYS.pounder");
			File indexFile = new File(externalDir, "firstname.idx");
			
			// Copy the index to the primary
			
			FileUtils.copyFile(indexFile,    new File(internalDir,  "firstname.idx"));
			
			// Copy the oldest segment to the primary
			
			List<File> list = Arrays.asList(segments);
			logger.info("segment count in test is " + list.size());
			Collections.sort(list, (a, b) -> a.getName().compareTo(b.getName()));

			FileUtils.copyFile(list.get(0), new File(internalDir, list.get(0).getName()));
			
			// Start the internal server and wait until the recovery is complete
			
			logger.info("Starting internal server");
			
			final int start = count + 1;
			Thread thread = new Thread(() -> this.backgroundJob(start));
			logger.info("Starting background");
			thread.start();
			
			
			super.startInternal("./replication.properties");
			
			Client internalClient = new Client(getLocalURI("8080"), "*", "admin", "admin");
			
			
			internalClient.waitServerCount(2);
			
			this.needExtra = false;
			thread.join();
						
			// Give the server a chance to catch up
			
			logger.info("Catching up");
			Utils.sleepSafely(10 * 1000);
			
			logger.info("after sleep");
			
			// Verify that all of the documents and index entries are in 
			// the internal server	
			
			String nameQuery = "select * from pounder where FirstName = ?";
			String idQuery = "select * from pounder where ID = ?";
			String extraQuery = "select * from extra where ID = ?";
			
			
			logger.info("Starting validation " + count);
			
			for (int idx = 0; idx < count; idx += 500) {
				int id = this.getId(idx);
				String name = this.getName(idx);
				
				DocumentSet ids = internalClient.executeQuery(idQuery, id);
				DocumentSet names = internalClient.executeQuery(nameQuery, name);
				
				if (ids.size() != 1) {
					logger.info("Missing id " + id);
					ids = internalClient.executeQuery(idQuery, id);
					ids = external.executeQuery(idQuery, id);					
				}
				
				Assertions.assertThat(ids.size() == 1).isTrue();
				Assertions.assertThat(ids.iterator().next().get("ID").equals(Long.valueOf(id))).isTrue();
				
				if (names.size() != 1) {
					logger.info("Bad names " + names.size() + " name = " + name + " id = " + id);
				}
				
				Assertions.assertThat(names.size() == 1).isTrue();
				Assertions.assertThat(ids.iterator().next().get("FirstName").equals(name)).isTrue();
			}
			
			
			logger.info("Checking extras");
			
			int badCount = 0;
			
			for (int idx = 0; idx < this.extraCount; idx++) {
				int key = this.getId(start + idx);
				
				DocumentSet set = internalClient.executeQuery(extraQuery, key);
				
				if (set.size() != 1) {
					badCount++;
				}
				
				//Assertions.assertThat(set.size() == 1).withFailMessage("Np extra").isTrue();
			}
			
			
			logger.info("After check loop extra " + extraCount + " bad = " + badCount);
			
			Assertions.assertThat(badCount < 50).withFailMessage("Bad count" + badCount).isTrue();
			//Utils.sleepSafely(10 * 60 * 1000);
		} catch (AssertionFailedError err) {
			throw err;
		} catch(Throwable exc) {
			exc.printStackTrace();
			Assertions.fail(exc.getMessage());
		} 
	}
	
	private void backgroundJob(int start) {
		try {
			
					
			Client client = new Client(getLocalURI("8081"), "*", "admin", "admin");
			String drop = "drop table if exists extra";
			String create = "CREATE TABLE extra (  ID int NOT NULL PRIMARY KEY, LastName varchar(255) NOT NULL default 'art', FirstName varchar(255), Age int)";
			String createIndex = "CREATE INDEX firstname ON extra(FirstName)";
			String insert = "insert into extra (ID, FirstName, LastName, Age) values (?, ?, 'grand', '1')";
			
			client.execute(drop);
			client.execute(create);
			client.execute(createIndex);
			
			for (int idx = start; this.needExtra; idx++) {
				client.execute(insert, getId(idx), getName(idx));	
				this.extraCount++;
			}
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
