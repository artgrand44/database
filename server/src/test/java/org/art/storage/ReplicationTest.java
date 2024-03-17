package org.art.storage;

import java.io.File;

import org.apache.commons.io.FileUtils;
import org.art.db.client.Client;
import org.art.db.common.Document;
import org.art.db.common.DocumentSet;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ReplicationTest extends BaseClientTest {
	private static final Logger logger = LoggerFactory.getLogger(ReplicationTest.class);
	
	private void load(Client client) throws Exception {
		logger.info("Got client");
		String create = "CREATE TABLE insert (id int NOT NULL PRIMARY KEY, last varchar(255) NOT NULL, first varchar(255), count int)";
		String createIndex = "CREATE INDEX name ON insert(first)";
		String insert = "insert into insert (id, first, last, count) values ('1', 'art', 'grand', '1')";
		
		client.execute(create);
		client.execute(createIndex);
		
		client.execute(insert);
	}
	@Test
	public void test() {
		
			try  {
				File secondDir = new File("./secondroot");
				
				if (secondDir.exists()) {
					FileUtils.deleteDirectory(secondDir);
				}
				
				super.startInternal("./replication.properties");
				super.startExternal("./secondary.properties");
				
				Client primaryClient = new Client(getLocalURI("8080"), "*", "admin", "admin");
				Client secondaryClient = new Client(getLocalURI("8081"), "*", "admin", "admin");
				
				primaryClient.waitServerCount(2);
				secondaryClient.waitServerCount(2);
				
				this.load(primaryClient);
				
				logger.info("after loads");
				
				String insertTemplare = "insert into insert (id, first, last, count) values ('%s', 'art', 'grand', '1')";
				String queryTemplate = "select * from insert where id = '%s'";
				
				
				for (int idx = 2; idx < 100; idx++) {
					String theInsert = String.format(insertTemplare, idx);
					primaryClient.execute(theInsert);
				}
				
				stopInternal();
				
				Thread.sleep(10 * 1000);
				
				logger.info("after sleep");
				for (int idx = 2; idx < 20; idx++) {
					String query= String.format(queryTemplate, idx);
					
					DocumentSet set = secondaryClient.executeQuery(query);
					
					Assertions.assertThat(!set.isEmpty()).isTrue();
					
					Document doc = set.iterator().next();
					
					Assertions.assertThat(doc.get("id").equals(Long.valueOf(idx))).isTrue();
				}
		} catch (AssertionFailedError err) {
			throw err;
		} catch(Throwable exc) {
			exc.printStackTrace();
			Assertions.fail(exc.getMessage());
		} 
	}

}
