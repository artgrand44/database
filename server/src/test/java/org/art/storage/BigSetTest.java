package org.art.storage;

import org.art.db.client.Client;
import org.art.db.common.Document;
import org.art.db.common.DocumentSet;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BigSetTest extends BaseIntegrationTest {
	
	private static final Logger logger = LoggerFactory.getLogger(BigSetTest.class);
	
	
	@Test
	public void test() {
		
			try  {
				startInternal("./application.properties");
				
				Client client = new Client("cluster", "admin", "admin").batch(false);
				
				String create = "CREATE TABLE bigset (id int NOT NULL PRIMARY KEY, first varchar(255), last varchar(255), count int)";
				String insert = "insert into bigset (id, first, last, count ) values (?, 'art', ?, ?)";
				String createIndex = "CREATE INDEX bigset ON bigset(count)";
				String query = "select * from bigset where count < 5000 order by count";
				
				client.execute(create);
				client.execute(createIndex);
				
				for (int idx = 0; idx < 2000; idx++) {
					client.execute(insert, idx, "last" + idx, idx);
				}
				
				DocumentSet set = client.executeQuery(query);
					
				if (set.size() != 2000) {
					long actual = 0;
					
					for (@SuppressWarnings("unused") Document doc : set) {
						actual++;
					}
					
					logger.info("size " + set.size() + " actual " + actual);
					
					
				}
				Assertions.assertThat(set.size() == 2000).isTrue();
				
				int count = 0;
				
				for (Document doc : set) {
					if (!doc.get("count").equals(Long.valueOf(count))) {
						logger.info("mismatch doc = " + doc.get("count") + " count " + count);
					}
					Assertions.assertThat(doc.get("count").equals(Long.valueOf(count))).isTrue();
					count++;
				}
		} catch (AssertionFailedError err) {
			throw err;
		} catch(Throwable exc) {
			exc.printStackTrace();
			Assertions.fail(exc.getMessage());
		} 
	}

}
