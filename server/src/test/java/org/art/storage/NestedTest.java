package org.art.storage;

import java.util.Objects;

import org.art.db.client.Client;
import org.art.db.common.Document;
import org.art.db.common.DocumentSet;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NestedTest extends BaseIntegrationTest {
	
	@SuppressWarnings("unused")
	private static final Logger logger = LoggerFactory.getLogger(NestedTest.class);
	
	
	@Test
	public void test() {
		
			try  {
				startInternal("./application.properties");
				
				Client client = this.getClient().batch(false);
				
				String create = "CREATE TABLE nested (id int NOT NULL PRIMARY KEY, doc Document)";
				String insert = "insert into nested (id, doc ) values ('1', ?)";
				String update = "update nested set doc.hello = 'hi art' where id = '1'";
				String query1 = "select * from nested where id = '1'";
				String query2 = "select * from nested where id = '1' and doc.hello = 'hi art'";
				
				
				client.execute(create);
				client.execute(insert, new Document());
				client.execute(update);
				
				DocumentSet set = client.executeQuery(query1);
				
				Assertions.assertThat(set.iterator().hasNext()).isTrue();
				Document doc = set.iterator().next();
				
				Assertions.assertThat(Objects.equals(doc.get("doc.hello"), "hi art")).isTrue();
				
				set = client.executeQuery(query2);
				
				Assertions.assertThat(set.iterator().hasNext()).isTrue();
				doc = set.iterator().next();
				
				Assertions.assertThat(Objects.equals(doc.get("doc.hello"), "hi art")).isTrue();
				
				
		} catch (AssertionFailedError err) {
			throw err;
		} catch(Throwable exc) {
			exc.printStackTrace();
			Assertions.fail(exc.getMessage());
		} 
	}

}
