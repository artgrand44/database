package org.art.storage;

import java.net.InetAddress;

import org.art.db.client.Client;
import org.art.db.common.ConnectionInfo;
import org.art.db.common.Document;
import org.art.db.common.DocumentSet;
import org.art.db.common.RestClient;
import org.art.db.common.Utils;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PrometheusTest extends BaseIntegrationTest {
	private static final Logger logger = LoggerFactory.getLogger(ReplicationTest.class);
	
	private void load(Client client) throws Exception {
		String drop = "drop table if exists insert";
		String create = "CREATE TABLE insert (id int NOT NULL PRIMARY KEY, last varchar(255) NOT NULL, first varchar(255), count int)";
		String createIndex = "CREATE INDEX name ON insert(first)";
		
		client.execute(drop);
		client.execute(create);
		client.execute(createIndex);
	}
	
	@Test
	public void test() {
		
			try  {
				String host = InetAddress.getLocalHost().getHostAddress();
				ConnectionInfo primary = new ConnectionInfo(host, "8080", "cluster", "shard1");
				
				startInternal("./prometheus.properties");
				
				Client primaryClient = this.getClient().batch(false);
				
				this.load(primaryClient);
				
				logger.info("after loads");
				
				String insert = "insert into insert (id, first, last, count) values (?, 'art', 'grand', '1')";
				String query = "select * from insert where id = ?";
				
				
				for (int idx = 2; idx < 100; idx++) {
					primaryClient.execute(insert, idx);
				}
				
				
				for (int idx = 2; idx < 20; idx++) {
					DocumentSet set = primaryClient.executeQuery(query, idx);
					
					Assertions.assertThat(!set.isEmpty());
					
					Document doc = set.iterator().next();
					
					Assertions.assertThat(doc.get("id").equals(Long.valueOf(idx)));
				}
				
				Thread.sleep(3000);
				
				RestClient client = new RestClient();
				
				client.setUser("admin");
				client.setPassword("admin");
				
				String stats = new String(client.get(client.buildURI(primary.toURI(), "/metrics")));
				
				Assertions.assertThat(stats != null);
				
				logger.info(stats);
				
				logger.info("sleep");
				
				for (;;) {
					for (int idx = 2; idx < 200; idx++) {
						primaryClient.executeQuery(query, idx % 20);
					}
					Utils.sleepSafely(100);
					//break;
				}
				
		} catch (AssertionFailedError err) {
			throw err;
		} catch(Throwable exc) {
			exc.printStackTrace();
			Assertions.fail(exc.getMessage());
		} 
	}

}

