package org.art.storage;

import org.art.client.Client;
import org.art.db.common.Configuration;
import org.art.db.common.Document;
import org.art.db.common.DocumentSet;
import org.assertj.core.api.Assertions;
import org.opentest4j.AssertionFailedError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BaseClientTest extends BaseIntegrationTest {
	private static final Logger logger = LoggerFactory.getLogger(BaseClientTest.class);
	
	protected void doTest(Configuration config) {
		try {
			logger.info("start");
			Client client = config == null ? Client.DUMMY : new Client(config);
			
			logger.info("Got client");
			String create = "CREATE TABLE insert (id int NOT NULL PRIMARY KEY, last varchar(255) NOT NULL, first varchar(255), count int)";
			String createIndex = "CREATE INDEX name ON insert(first)";
			
			client.execute(create);
			client.execute(createIndex);
			
			String insert = "insert into insert (id, first, last, count) values ('1', 'art', 'grand', '1')";
			
			String update = "update insert set count='2' where id = '1'";
			String delete = "delete from insert where id = '1'";
			String query = "select * from insert where id = '1'";
			
			client.execute(insert);
			
			DocumentSet docs = client.executeQuery(query);
			
			Assertions.assertThat(docs.size() == 1).isTrue();
			
			Document first = docs.iterator().next();
			
			Assertions.assertThat("art".equals(first.get("first"))).isTrue();
			Assertions.assertThat("grand".equals(first.get("last"))).isTrue();
			
			client.execute(update);
			
			docs = client.executeQuery(query);
			
			Assertions.assertThat(docs.size() == 1).isTrue();
			
			first = docs.iterator().next();
			Assertions.assertThat(Long.valueOf(2).equals(first.get("count"))).isTrue();
			
			client.execute(delete);
			
			docs = client.executeQuery(query);
			
			Assertions.assertThat(docs.size() == 0).isTrue();
				
			String ordered = "select * from insert where first < 'first1000' order by first";
			String insertTemplate = "insert into insert (id, first, last, count) values (%s, 'art%s', 'grand%s', '1')";
			
			for (int idx = 0; idx < 10; idx++) {
				client.execute(String.format(insertTemplate, idx, idx, idx));
			}
			
			docs = client.executeQuery(ordered);
			
			int count = 0;
			
			for (Document doc : docs) {
				Assertions.assertThat(doc.get("first").equals("art" + count)).isTrue();
				count++;
			}
			
			String insertJoe  = "insert into insert (id, first, last, count) values ('5000', 'joe', 'grands', '1')";
			
			client.execute(insertJoe);
			
			String art0 = "select * from insert where first = 'joe' order by first";
			
			docs = client.executeQuery(art0);
			
			Assertions.assertThat(docs.size() == 1).isTrue();
			Assertions.assertThat(docs.iterator().next().get("first").equals("joe")).isTrue();
		} catch (AssertionFailedError err) {
			throw err;
		} catch(Exception exc) {
			exc.printStackTrace();
			Assertions.fail(exc.getMessage());
		}

	}
}
