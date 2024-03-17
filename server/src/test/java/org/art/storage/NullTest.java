package org.art.storage;

import java.util.Objects;

import org.art.db.client.Client;
import org.art.db.common.Document;
import org.art.db.common.DocumentSet;
import org.art.db.common.DBNull;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NullTest extends BaseClientTest {
	
	@SuppressWarnings("unused")
	private static final Logger logger = LoggerFactory.getLogger(NullTest.class);
	
	
	@Test
	public void test() {
		
			try  {
				startInternal("./application.properties");
				
				Client client = new Client(getLocalURI("8080"), "*", "admin", "admin");
				
				String create = "CREATE TABLE nulls (id int NOT NULL PRIMARY KEY, first varchar(255), last varchar(255))";
				String createIndex = "CREATE INDEX nulls ON nulls(first)";
				
				String insert = "insert into nulls (id, first, last ) values ('1', 'art', NULL)";
				String update = "update nulls set first = NULL where id = '1'";
				String query1 = "select * from nulls where id = '1'";
				
				client.execute(create);
				client.execute(createIndex);
				
				client.execute(insert);
				
				DocumentSet set = client.executeQuery(query1);
				
				Assertions.assertThat(set.iterator().hasNext());
				Document doc = set.iterator().next();
				
				Assertions.assertThat(Objects.equals(doc.get("first"), "art"));
				Assertions.assertThat(Objects.equals(doc.get("last"),  DBNull.NULL));
				
				client.execute(update);
				
				set = client.executeQuery(query1);
				
				Assertions.assertThat(set.iterator().hasNext()).isTrue();
				doc = set.iterator().next();
				
				Assertions.assertThat(Objects.equals(doc.get("first"), DBNull.NULL)).isTrue();
				
				String insertMore = "insert into nulls (id, first, last ) values (?, ?, ?)";
				String queryMore = "select * from nulls where first < 'art1000' order by last";
				
				for (int idx = 2; idx < 100; idx++) {
					client.execute(insertMore, String.valueOf(idx), "art" + idx, idx % 2 == 0 ? DBNull.NULL : "last" + idx);
				}
				
				set = client.executeQuery(queryMore);
				boolean valueSeen = false;
				
				Assertions.assertThat(set.iterator().hasNext()).isTrue();
				
				for (Document other : set) {
					Object last = other.get("last");
					
					if (last instanceof DBNull) {
						Assertions.assertThat(!valueSeen).isTrue();
					} else {
						valueSeen = true;
					}
				}
				
				
		} catch (AssertionFailedError err) {
			throw err;
		} catch(Throwable exc) {
			exc.printStackTrace();
			Assertions.fail(exc.getMessage());
		} 
	}

}
