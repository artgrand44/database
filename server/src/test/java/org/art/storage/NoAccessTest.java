package org.art.storage;

import org.art.db.client.Client;
import org.art.db.common.DocumentSet;
import org.opentest4j.AssertionFailedError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

public class NoAccessTest extends BaseClientTest {
	
	@SuppressWarnings("unused")
	private static final Logger logger = LoggerFactory.getLogger(NoAccessTest.class);
	
	
	@Test
	public void test() {
		
			try  {
				startInternal("./application.properties");
				
				String create = "CREATE TABLE privtest (id int NOT NULL PRIMARY KEY, first varchar(255), last varchar(255))";
				String insert = "insert into privtest (id, first, last ) values (?, 'art', ?)";
				String createIndex = "CREATE INDEX privtest ON privtest(last)";
				String query = "select * from privtest where last < 'last3000' order by last";
				String createUser = "create user foo password bar";
				String grant = "GRANT select, insert ON privtest TO foo";
				
				Client userClient = new Client("cluster", "foo", "bar");
				
				try {
					userClient.execute(create);
					Assertions.fail("userClient should not be able to create");
				} catch (Exception exc) {
					
				}
				
				try {
					userClient.execute(createIndex);
					Assertions.fail("userClient should not be able to create index");
				} catch (Exception exc) {
					
				}
				
				try {
					userClient.execute(createUser);
					Assertions.fail("userClient should not be able to create user");
				} catch (Exception exc) {
					
				}
				
				try {
					userClient.execute(grant);
					Assertions.fail("userClient should not be able to grant");
				} catch (Exception exc) {
					
					
				}
				Client adminClient = new Client("cluster", "admin", "admin");
				
				adminClient.execute(create);
				adminClient.execute(createIndex);
				adminClient.execute(createUser);
				adminClient.execute(grant);
				
				
				for (int idx = 0; idx < 20; idx++) {
					userClient.execute(insert, idx, "last" + idx);
				}
				
				DocumentSet set = userClient.executeQuery(query);
					
				Assertions.assertThat(set.size() != 0).isTrue();
		} catch (AssertionFailedError err) {
			throw err;
		} catch(Throwable exc) {
			exc.printStackTrace();
			Assertions.fail(exc.getMessage());
		} 
	}

}

