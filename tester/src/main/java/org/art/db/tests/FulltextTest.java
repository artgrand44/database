package org.art.db.tests;


import org.art.db.client.Client;
import org.art.db.common.DocumentSet;
import org.art.db.common.GUID;
import org.art.db.common.Utils;
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
			String create = "CREATE TABLE fulltext (id GUID NOT NULL PRIMARY KEY, text varchar(255), nontext varchar(255), nonindex varchar(255))";
			String createTextIndex = "CREATE INDEX name1 ON fulltext(text) (FULLTEXT)";
			String createNonIndex = "CREATE INDEX name2 ON fulltext(nontext)";
			String createMoreIndex = "CREATE INDEX name3 ON fulltext(nonindex) (FULLTEXT)";
			
			client.execute(drop);
			client.execute(create);
			client.execute(createTextIndex);
			client.execute(createNonIndex);
			
			GUID guid1 = new GUID();
			GUID guid2 = new GUID();
			
			client.execute("insert into fulltext (id, text, nontext, nonindex ) values (?, 'when in the course of human events', 'nontext', 'lots of stuff')", guid1);
			client.execute("insert into fulltext (id, text, nontext , nonindex) values (?, 'human go nuts', 'nontext', 'more stuff')", guid2);
			
			DocumentSet docs = client.executeQuery("select * from fulltext where match(text) against('human')");
			
			Assertions.assertThat(docs.size() == 2).isTrue();
			Assertions.assertThat(docs.iterator().next().get("text", String.class).contains("human")).isTrue();
			Assertions.assertThat(docs.iterator().next().get("text", String.class).contains("human")).isTrue();
			
			docs = client.executeQuery("select * from fulltext where match(text) against('go nuts')");
			
			Assertions.assertThat(docs.size() == 1).isTrue();
			Assertions.assertThat(docs.iterator().next().get("text", String.class).contains("go nuts")).isTrue();
			
			try {
				docs = client.executeQuery("select * from fulltext where match(nontext) against('human')");
				Assertions.fail("match on nontext should fail");
			} catch (Exception exc) {
				
			}
			
			try {
				docs = client.executeQuery("select * from fulltext where text = 'human'");
				Assertions.fail("text = human should fail");
			} catch (Exception exc) {
				
			}
			
			docs = client.executeQuery("select * from fulltext where match(text) against('nuts') or match(text) against('events')");
			Assertions.assertThat(docs.size() == 2).isTrue();
			
			docs = client.executeQuery("select * from fulltext where match(text) against('nuts') and match(text) against('events')");
			Assertions.assertThat(docs.size() == 0).isTrue();
			
			docs = client.executeQuery("select * from fulltext where match(text) against('nuts') and match(text) against('nuts')");
			Assertions.assertThat(docs.size() == 1).isTrue();
			
			client.execute("update fulltext set text = 'cats are wise' where id = ?", guid2);
			
			docs = client.executeQuery("select * from fulltext where match(text) against('cats')");
			Assertions.assertThat(docs.size() == 1).isTrue();
			
			docs = client.executeQuery("select * from fulltext where match(text) against('nuts')");
			Assertions.assertThat(docs.size() == 0).isTrue();
			
			client.execute(createMoreIndex);
			
			boolean found = false;
			
			for (int idx = 0; idx < 100; idx++) {
				docs = null;
				
				try {
					docs = client.executeQuery("select * from fulltext where match(nonindex) against('stuff')");
				} catch (Exception exc) {
					
				}
				
				if (docs != null && docs.size() != 0) {
					found = true;
					break;
				}
				
				Utils.sleepSafely(1000);
			}
			
			Assertions.assertThat(found).isTrue();
		} catch (AssertionFailedError err) {
			throw err;
		} catch(Exception exc) {
			exc.printStackTrace();
			Assertions.fail(exc.getMessage());
		}
		
	}
}
