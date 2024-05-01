package org.art.storage;

import java.net.InetAddress;

import org.art.db.client.Client;
import org.art.db.common.Configuration;
import org.art.db.common.Document;
import org.art.db.common.DocumentSet;
import org.art.db.common.RestClient;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class UITest extends BaseClientTest {
	private static final Logger logger = LoggerFactory.getLogger(ReplicationTest.class);
	
	private void load(Client client) throws Exception {
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
				String host = InetAddress.getLocalHost().getHostAddress();
				Configuration primary = new Configuration(host, "8080", "cluster", "shard1").withProperty("org.art.db.dbroot", "./primaryroot");
				
				startInternal("./application.properties");
				
				Client primaryClient = new Client("cluster", "admin", "admin");
				
				this.load(primaryClient);
				
				logger.info("after loads");
				
				String insertTemplare = "insert into insert (id, first, last, count) values ('%s', 'art', 'grand', '1')";
				String queryTemplate = "select * from insert where id = '%s'";
				
				
				for (int idx = 2; idx < 100; idx++) {
					String theInsert = String.format(insertTemplare, idx);
					primaryClient.execute(theInsert);
				}
				
				
				for (int idx = 2; idx < 20; idx++) {
					String query = String.format(queryTemplate, idx);
					
					DocumentSet set = primaryClient.executeQuery(query);
					
					Assertions.assertThat(!set.isEmpty());
					
					Document doc = set.iterator().next();
					
					Assertions.assertThat(doc.get("id").equals(Long.valueOf(idx)));
				}
				
				Thread.sleep(3000);
				
				RestClient client = new RestClient();
				
				client.setUser("admin");
				client.setPassword("admin");
				
				String html = new String(client.get(client.buildURI(primary.toURI(), "/db/ui")));
				
				Assertions.assertThat(html != null);
				
				logger.info(html);
				
				//logger.info("sleep");
				//Thread.sleep(1000 * 60 * 19);
		} catch (AssertionFailedError err) {
			throw err;
		} catch(Throwable exc) {
			exc.printStackTrace();
			Assertions.fail(exc.getMessage());
		} 
	}

}

