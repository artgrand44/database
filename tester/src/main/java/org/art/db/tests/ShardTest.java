package org.art.db.tests;

import org.art.db.client.Client;
import org.art.db.common.ConnectionInfo;
import org.art.db.common.DiscoveryHandler;
import org.art.db.common.Document;
import org.art.db.common.DocumentSet;
import org.art.db.common.Key;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ShardTest extends BaseIntegrationTest {
	private static final Logger logger = LoggerFactory.getLogger(ShardTest.class);
	
	private void load(Client client) throws Exception {
		String drop = "drop table if exists sharded";
		String create = "CREATE TABLE sharded (id  varchar(255) NOT NULL PRIMARY KEY, color varchar(255) NOT NULL, make varchar(255), count int)";
		String createIndex = "CREATE INDEX name ON sharded(color)";
		
		client.execute(drop);
		client.execute(create);
		client.execute(createIndex);
		
	}
	
	@Test
	//@RepeatedTest(20)
	public void test() {
		
			try {
				TestAdapter.getInstance().startPrimary("./averlongshardname.properties");
				TestAdapter.getInstance().startSecondary("./cluster2.properties");
				
				
				Client primaryClient = TestAdapter.getInstance().getPrimaryClient("admin", "admin").batch(false);
				Client secondaryClient = TestAdapter.getInstance().getSecondaryClient("admin", "admin").batch(false);
				Client clusterClient = TestAdapter.getInstance().getClusterClient("admin", "admin").batch(false);
				
				primaryClient.waitServerCount(2);
				secondaryClient.waitServerCount(2);
				
				this.load(primaryClient);
				this.load(secondaryClient);
				
				logger.info("after loads");
				
				String insertTemplare = "insert into sharded (id, color, make, count) values ('%s', 'red', 'toyota', '1')";
				String queryTemplate = "select * from sharded where id = '%s'";
				
				ConnectionInfo info = new ConnectionInfo(null, "8080", "*", "defaultcluster");
				DiscoveryHandler handler = DiscoveryHandler.getHandler(info);
				
				handler.waitInstanceCount(2, 10 * 1000);
				
				for (int idx = 0; idx < 10; idx++) {
					String keyValue = "Hello" + idx;
					Key key = new Key(keyValue);
					
					String shard = handler.getShard(key.toString());
					
					logger.info("insert into primary" + keyValue + " shard = "  + shard);
					
					String theInsert = String.format(insertTemplare, keyValue);
					
					primaryClient.execute(theInsert);
					
					Client expected = shard.equals("averlongshardname") ? primaryClient : secondaryClient;
					Client other = shard.equals("averlongshardname") ? secondaryClient : primaryClient;
					String query = String.format(queryTemplate, keyValue);
					
					DocumentSet set = expected.executeQuery(query);
					
					if (set.isEmpty()) {
						logger.info("empty key = " + key + " shard = " + shard + " other = " + other.executeQuery(query).size());
					}
					
					Assertions.assertThat(!set.isEmpty()).isTrue();
					
					Document doc = set.iterator().next();
					
					Assertions.assertThat(doc.get("id").equals(keyValue)).isTrue();
				}
				
				DocumentSet set = clusterClient.executeQuery("select * from sharded where color = 'red' order by id");
				
				if (set.size() != 10) {
					logger.info("**** size is " + set.size());
					
					for (Document doc : set) {
						logger.info("doc = " + doc);
					}
				}
				Assertions.assertThat(set.size() == 10).isTrue();
				
				logger.info("bad select");
				set = clusterClient.executeQuery("select * from sharded where color = 'red' order by id limit 5");
				
				if (set.size() != 5) {
					logger.info("**** size is " + set.size());
					
					for (Document doc : set) {
						logger.info("doc = " + doc);
					}
				}
				
				Assertions.assertThat(set.size() == 5).isTrue();
				
				clusterClient.execute("update sharded set color = 'blue' where color = 'red'");
				
				set = clusterClient.executeQuery("select * from sharded where color = 'blue'");
				
				Assertions.assertThat(set.size() == 10).isTrue();
				
				set = clusterClient.executeQuery("update sharded set color = 'purple' where color = 'blue' limit 5 returning *");
				
				logger.info("after update " + set.size());
				Assertions.assertThat(set.size() == 5).isTrue();
				
				clusterClient.execute("update sharded set color = 'blue' where color = 'purple'");
				
				set = clusterClient.executeQuery("delete from sharded where color = 'blue' limit 5 returning *");
				logger.info("after delete " + set.size());
				Assertions.assertThat(set.size() == 5).isTrue();
				
				clusterClient.execute("delete from sharded where color = 'blue'");
				
				set = clusterClient.executeQuery("select * from sharded where color = 'blue'");
				
				Assertions.assertThat(set.size() == 0).isTrue();
		} catch (AssertionFailedError err) {
			err.printStackTrace();
			throw err;
		} catch(Throwable exc) {
			exc.printStackTrace();
			Assertions.fail(exc.getMessage());
		} 	
			
	}

}
