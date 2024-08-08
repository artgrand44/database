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
		
			try  {
				TestRunner.getAdapter().startPrimary("./averlongshardname.properties");
				TestRunner.getAdapter().startSecondary("./cluster2.properties");
				
				
				Client averlongshardnameClient = TestRunner.getAdapter().getPrimaryClient("admin", "admin").batch(false);
				Client shard2Client = TestRunner.getAdapter().getSecondaryClient("admin", "admin").batch(false);
				Client clusterClient = TestRunner.getAdapter().getClusterClient("admin", "admin").batch(false);
				
				averlongshardnameClient.waitServerCount(2);
				shard2Client.waitServerCount(2);
				
				this.load(averlongshardnameClient);
				this.load(shard2Client);
				
				logger.info("after loads");
				
				String insertTemplare = "insert into sharded (id, color, make, count) values ('%s', 'red', 'toyota', '1')";
				String queryTemplate = "select * from sharded where id = '%s'";
				
				DiscoveryHandler handler = new DiscoveryHandler(new ConnectionInfo("./averlongshardname.properties"), false);
				handler.waitInstanceCount(2, 10 * 1000);
				
				for (int idx = 0; idx < 10; idx++) {
					String keyValue = "Hello" + idx;
					Key key = new Key(keyValue);
					
					String shard = handler.getShard(key.toString());
					
					String theInsert = String.format(insertTemplare, keyValue);
					
					averlongshardnameClient.execute(theInsert);
					
					Client expected = shard.equals("averlongshardname") ? averlongshardnameClient : shard2Client;
					String query = String.format(queryTemplate, keyValue);
					
					DocumentSet set = expected.executeQuery(query);
					
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
			throw err;
		} catch(Throwable exc) {
			exc.printStackTrace();
			Assertions.fail(exc.getMessage());
		} 	}

}
