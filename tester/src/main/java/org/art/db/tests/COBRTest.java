package org.art.db.tests;

import org.art.db.client.Client;
import org.art.db.common.Batch;
import org.art.db.common.DocumentSet;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class COBRTest extends BaseIntegrationTest {

	@SuppressWarnings("unused")
	private static final Logger logger = LoggerFactory.getLogger(COBRTest.class);

	
	@Test
	public void test() {

		try  {
			TestAdapter.getInstance().startPrimary("./application.properties");

			Client client = TestAdapter.getInstance().getClusterClient("admin", "admin").batch(false);
			
			client.cbor = true;
			String drop = "drop table if exists grous";
			String create = "CREATE TABLE batch (id int NOT NULL PRIMARY KEY, country varchar(255), state varchar(255), value int)";
			String createIndex = "CREATE INDEX state ON batch(country)";
			
			
			client.execute(drop);
			client.execute(create);
			client.execute(createIndex);
			
			Batch batch = client.createBatch();
			String insert = "insert into batch (id, country, state, value) values (?, ?, ?, ?)";
			
			for (int idx = 0; idx < 100; idx++) {
				String state = "state" + (idx % 4);
				
				batch.add(insert, idx, "us", state, idx);
			}
			
			client.executeBatch(batch);
			
			for (int idx = 0; idx < 100; idx++) {
				DocumentSet set = client.executeQuery("select * from batch where id = ?", idx);
				
				Assertions.assertThat(set.size() == 1).isTrue();
			}
		} catch (AssertionFailedError err) {
			throw err;
		} catch(Throwable exc) {
			exc.printStackTrace();
			Assertions.fail(exc.getMessage());
		} 
	}
	
}

