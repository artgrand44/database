package org.art.storage;

import org.art.db.client.Client;
import org.art.db.common.DBRuntimeException;
import org.art.db.common.DocumentSet;
import org.art.db.common.Utils;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BuildIndexTest extends BaseIntegrationTest {
	
	private static final Logger logger = LoggerFactory.getLogger(BuildIndexTest.class);
	
	@Test
	public void test() {
		try {
			startInternal("./application.properties");
			
			Client client = this.getClient().batch(false);
			
			String create = "CREATE TABLE builder (  ID int NOT NULL PRIMARY KEY, LastName varchar(255) NOT NULL default 'art', FirstName varchar(255), Age int)";
			String createIndex = "CREATE INDEX buildme ON builder(FirstName)";
			String dropIfExists = "DROP table if exists builder";
			String insert = "insert into builder (ID, LastName, FirstName,  Age) values (?, 'grand', 'art', 10)";
			
					
			client.execute(dropIfExists);
			client.execute(create);
			
			
			for (int idx = 0; idx < 100; idx++) {
				client.execute(insert, idx);
			}
			
			client.execute(createIndex);
			
			for (int idx = 0; idx < 1000; idx++) {
				try {
					DocumentSet set = client.executeQuery("select * from builder where FirstName = 'art'");
				
					Assertions.assertThat(set.size() == 100).isTrue();
					break;
				} catch (DBRuntimeException exc) {
					if (exc.getMessage().equals("The query does must include one of more indexed fields")) {
						Utils.sleepSafely(1000);
					}
				}
			}
		
		} catch (AssertionFailedError err) {
			throw err;
		} catch(Exception exc) {
			exc.printStackTrace();
			Assertions.fail(exc.getMessage());
		}
	}

}
