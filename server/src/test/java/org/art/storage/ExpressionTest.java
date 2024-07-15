package org.art.storage;

import org.art.db.client.Client;
import org.art.db.common.DocumentSet;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ExpressionTest extends BaseIntegrationTest {
	
	@SuppressWarnings("unused")
	private static final Logger logger = LoggerFactory.getLogger(ExpressionTest.class);
	
	private void doQuery(Client client, String query) throws Exception {
		DocumentSet set = client.executeQuery(query);
		
		Assertions.assertThat(set.size() != 0).isTrue();
		
	}
	@Test
	public void test() {
		
			try  {
				startInternal("./application.properties");
				
				Client client = this.getClient().batch(false);
				
				String create = "CREATE TABLE expressions (id int NOT NULL PRIMARY KEY, first varchar(255), last varchar(255), count int)";
				String createIndex = "CREATE INDEX expressions ON expressions(first)";
				
				String insert = "insert into expressions (id, first, last, count ) values (?, 'art', 'grand', ?)";
				
				
				client.execute(create);
				client.execute(createIndex);
				
				for (int idx = 0; idx < 10; idx++) {
					client.execute(insert, idx, idx);
				}
				
				doQuery(client, "select * from expressions where id = 1 and 1<<1 <> 0");
				doQuery(client, "select * from expressions where id = 1 and 15>>1 <> 0");
				doQuery(client, "select * from expressions where id = 1 and 1 + 1 = 2");
				doQuery(client, "select * from expressions where id = 1 and 1 - 1 = 0");
				doQuery(client, "select * from expressions where 4 / 2 = 2 and id = 1 and 1 - 1 = 0");
				doQuery(client, "select * from expressions where id = 1 and 'a' + 'b' = 'ab'");
				doQuery(client, "select * from expressions where id = 1 and 'a' || 'b' = 'ab'");
				doQuery(client, "select * from expressions where id = 1 and 2 * 2 = 4");
				doQuery(client, "select * from expressions where id = 1 or 1 = 1");
				doQuery(client, "select * from expressions where id = 1 or (2 xor 2)");
				doQuery(client, "select * from expressions where id = 1 or 1 & 1");
				doQuery(client, "select * from expressions where id = 1 or 1 | 1");
				doQuery(client, "select * from expressions where id = 1 or ((10 % 2) = 5)");
				
				
		} catch (AssertionFailedError err) {
			throw err;
		} catch(Throwable exc) {
			exc.printStackTrace();
			Assertions.fail(exc.getMessage());
		} 
	}

}
