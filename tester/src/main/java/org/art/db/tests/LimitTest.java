package org.art.db.tests;

import org.art.db.client.Client;
import org.art.db.common.Document;
import org.art.db.common.DocumentSet;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LimitTest  extends BaseIntegrationTest {
	private static final Logger logger = LoggerFactory.getLogger(DAOTest.class);
	
	static interface Query {
		Iterable<Item> call(String key) throws Exception;
	}
	
	
	private Client client;
	
	public LimitTest() {
		
	}

	@BeforeEach
	public void beforeEach() throws Exception  {
		startPrimary("./application.properties");
		
		this.client = this.getClient().batch(false);
		
	}
	
	private void load() throws Exception {
		String drop = "drop table if exists sharded";
		String create = "CREATE TABLE sharded (id  varchar(255) NOT NULL PRIMARY KEY, color varchar(255) NOT NULL, make varchar(255), count int)";
		String createIndex = "CREATE INDEX name ON sharded(color)";
		
		client.execute(drop);
		client.execute(create);
		client.execute(createIndex);
		
	}
	
	@Test
	public void test() {
		logger.info("Start handlerTest");

		try {
			this.load();
			
			
			String insertTemplare = "insert into sharded (id, color, make, count) values ('%s', 'red', 'toyota', '1')";
			
			for (int idx = 0; idx < 10; idx++) {
				String keyValue = "Hello" + idx;
				
				String theInsert = String.format(insertTemplare, keyValue);
				client.execute(theInsert);
			}
			
			DocumentSet set = client.executeQuery("select * from sharded where color = 'red'");
			
			Assertions.assertThat(set.size() == 10).isTrue();
			
			client.execute("update sharded set color = 'blue' where color = 'red'");
			
			set = client.executeQuery("update sharded set color = 'purple' where color = 'blue' limit 5 returning *");
			
			Assertions.assertThat(set.size() == 5).isTrue();
			
			for (Document doc : set) {
				Assertions.assertThat(doc.get("color").equals("purple")).isTrue();
			}
			
			client.execute("update sharded set color = 'blue' where color = 'purple'");
			
			set = client.executeQuery("delete from sharded where color = 'blue' limit 2 returning *");
			
			Assertions.assertThat(set.size() == 2).isTrue();
			
			set = client.executeQuery("select * from sharded where color = 'blue'");
			
			Assertions.assertThat(set.size() == 8).isTrue();
			
			set = client.executeQuery("delete from sharded where color = 'blue' returning *");
			Assertions.assertThat(set.size() == 8).isTrue();
			
		} catch (AssertionFailedError exc) {
			throw exc;
		} catch (Exception exc) {
			exc.printStackTrace();
			Assertions.fail(exc.getMessage());
		}		
	}			
}
