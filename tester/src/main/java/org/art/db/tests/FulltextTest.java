package org.art.db.tests;


import org.art.db.client.Client;
import org.art.db.common.DocumentSet;
import org.art.db.common.GUID;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FulltextTest extends BaseIntegrationTest {
	
	@SuppressWarnings("unused")
	private static final Logger logger = LoggerFactory.getLogger(FulltextTest.class);
	
	
	@Test
	public void test() {
		try {
			TestAdapter.getInstance().startPrimary("./tinysegments.properties");
			
			Client client = TestAdapter.getInstance().getClusterClient("admin", "admin").batch(false);
			
			String drop = "drop table if exists fulltext";
			String create = "CREATE TABLE fulltext (id GUID NOT NULL PRIMARY KEY, text varchar(255))";
			String createNameIndex = "CREATE INDEX name ON fulltext(text) (FULLTEXT)";
			
			
			client.execute(drop);
			client.execute(create);
			client.execute(createNameIndex);
			
			GUID guid1 = new GUID();
			GUID guid2 = new GUID();
			
			client.execute("insert into fulltext (id, text ) values (?, 'when in the course of human events')", guid1);
			client.execute("insert into fulltext (id, text ) values (?, 'human go nuts')", guid2);
			
			DocumentSet docs = client.executeQuery("select * from fulltext where match(text) against('human')");
			
			Assertions.assertThat(docs.size() == 2).isTrue();
			Assertions.assertThat(docs.iterator().next().get("text", String.class).contains("human")).isTrue();
			Assertions.assertThat(docs.iterator().next().get("text", String.class).contains("human")).isTrue();
			
			docs = client.executeQuery("select * from fulltext where match(text) against('go nuts')");
			
			Assertions.assertThat(docs.size() == 1).isTrue();
			Assertions.assertThat(docs.iterator().next().get("text", String.class).contains("go nuts")).isTrue();
			
			
			client.execute("update fulltext set text = 'humans wise up' where id = ?", guid2);
			
		} catch (AssertionFailedError err) {
			throw err;
		} catch(Exception exc) {
			exc.printStackTrace();
			Assertions.fail(exc.getMessage());
		}
		
	}
}
