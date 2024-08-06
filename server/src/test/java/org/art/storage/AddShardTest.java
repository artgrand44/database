package org.art.storage;

import org.art.db.client.Client;
import org.art.db.common.Document;
import org.art.db.common.DocumentSet;
import org.art.db.common.Utils;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AddShardTest extends BaseIntegrationTest {
	
	private static final Logger logger = LoggerFactory.getLogger(AddShardTest.class);


	@Test
	public void test() {

		try  {
			super.startInternal("./averlongshardname.properties");

			String drop = "drop table if exists sharded";
			String create = "CREATE TABLE sharded (id  varchar(255) NOT NULL PRIMARY KEY, color varchar(255) NOT NULL, make varchar(255), count int)";
			String createIndex = "CREATE INDEX name ON sharded(color)";
			Client client = this.getClient().batch(false);

			client.execute(drop);
			client.execute(create);
			client.execute(createIndex);


			String insertTemplare = "insert into sharded (id, color, make, count) values ('%s', 'red', 'toyota', '1')";

			for (int idx = 0; idx < 10; idx++) {
				String keyValue = "Hello" + idx;
				String theInsert = String.format(insertTemplare, keyValue);

				client.execute(theInsert);
			}


			DocumentSet set = client.executeQuery("select * from sharded  where color = 'red' order by id");

			if (set.size() != 10) {
				logger.info("**** size is " + set.size());

				for (Document doc : set) {
					logger.info("doc = " + doc);
				}
			}

			super.startExternal("./cluster2.properties");
			Client shard2Client = new Client(getLocalURI("8081"), "shard2", "admin", "admin").batch(false);
			boolean started = false;
			
			for (int idx = 0; idx < 100; idx++) {
				try {
					shard2Client.waitServerCount(2);
					started = true;
				} catch(Exception exc) {
					Utils.sleepSafely(10000);
				}
			}
			
			Assertions.assertThat(started).isTrue();
			
			set = shard2Client.executeQuery("select * from sharded  where color = 'red' order by id");

			Assertions.assertThat(set.size() != 0).isTrue();
		} catch (AssertionFailedError err) {
			throw err;
		} catch(Throwable exc) {
			exc.printStackTrace();
			Assertions.fail(exc.getMessage());
		} 	
	}

}

