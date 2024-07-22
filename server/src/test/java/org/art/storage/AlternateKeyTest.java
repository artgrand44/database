package org.art.storage;


import org.art.db.common.Document;
import org.art.db.common.DocumentSet;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class AlternateKeyTest extends BaseSQLTest {
	
	@SuppressWarnings("unused")
	private static final Logger logger = LoggerFactory.getLogger(AlternateKeyTest.class);
	
	@Test
	public void test() {
		try {
			String drop = "drop table if exists alttest";
			String create = "CREATE TABLE alttest (  ID int NOT NULL PRIMARY KEY, LastName varchar(255) NOT NULL default 'art', FirstName varchar(255), Age int)";
			String createIndex = "CREATE INDEX firstname ON alttest(FirstName)";
			String insert = "insert into alttest (ID, FirstName, LastName, Age) values (?, ?, 'grand', '1')";
			String update = "update alttest set FirstName = ? where FirstName = ?";
			
			String query = "select * from alttest where FirstName = ?";
			
			execute(drop);
			execute(create);
			execute(createIndex);
			
			for (int idx = 0; idx < 1000; idx++) {
				execute(insert, idx, "Art" + idx);
			}
			
			for (int idx = 0; idx < 1000; idx++) {
				String key =  "Art" + idx;
				String newKey = "Sam" + idx;
				
				DocumentSet set = executeSelect(query, key);
				
				
				Assertions.assertThat(set.size() == 1).isTrue();
				
				Document doc = set.iterator().next();
				
				Assertions.assertThat(doc.get("FirstName").equals(key)).isTrue();
				
				execute(update, newKey, key);
				
				set = executeSelect(query, key);
				
				Assertions.assertThat(set.size() == 0).isTrue();
				
				set = executeSelect(query, newKey);
				
				Assertions.assertThat(set.size() == 1).isTrue();
				
				doc = set.iterator().next();
				
				Assertions.assertThat(doc.get("FirstName").equals(newKey)).isTrue();			
			}
		} catch (AssertionFailedError err) {
			throw err;
		} catch(Exception exc) {
			exc.printStackTrace();
			Assertions.fail(exc.getMessage());
		}
		
	}
}
