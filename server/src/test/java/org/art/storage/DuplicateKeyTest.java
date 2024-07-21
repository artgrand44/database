package org.art.storage;


import org.art.db.client.Client;
import org.art.db.common.DocumentSet;
import org.art.db.common.GUID;
import org.art.db.common.DBRuntimeException;
import org.art.db.storage.MemorySegment;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Integration
public class DuplicateKeyTest extends BaseIntegrationTest {
	
	@SuppressWarnings("unused")
	private static final Logger logger = LoggerFactory.getLogger(MemorySegment.class);
	
	
	@Test
	public void test() {
		try {
			startInternal("./tinysegments.properties");
			
			Client client = this.getClient().batch(false);
			
			String drop = "drop table if exists duptest";
			String create = "CREATE TABLE duptest (id GUID NOT NULL PRIMARY KEY, name varchar(255), color varchar(255))";
			String createNameIndex = "CREATE UNIQUE INDEX name ON duptest(name)";
			String createColorIndex = "CREATE INDEX name ON duptest(color)";
			
			client.execute(drop);
			client.execute(create);
			client.execute(createNameIndex);
			client.execute(createColorIndex);
			
			GUID guid1 = new GUID();
			GUID guid2 = new GUID();
			
			client.execute("insert into duptest (id, name, color ) values (?, 'art', 'red')", guid1);
			client.execute("insert into duptest (id, name, color ) values (?, 'sam', 'red')", guid2);
			
			try {
				client.execute("insert into duptest (id, name, color ) values (?, 'art', 'blue')", guid1);
				Assertions.fail("Duplicate primary not detected");
			} catch (DBRuntimeException exc) {
				
			}
			
			client.execute("update duptest set color = 'blue' where id = ?", guid1);
			
			try {
				client.execute("update duptest set name = 'sam' where id = ?", guid1);
				Assertions.fail("Duplicate name not detected");
			} catch (DBRuntimeException exc) {
				
			}
			
			DocumentSet docs = client.executeQuery("select * from duptest where name = 'art'");
			
			Assertions.assertThat(docs.size() == 1).isTrue();
			Assertions.assertThat(docs.iterator().next().get("name").equals("art")).isTrue();
			
			docs = client.executeQuery("select * from duptest where name = 'sam'");
			
			Assertions.assertThat(docs.size() == 1).isTrue();
			Assertions.assertThat(docs.iterator().next().get("name").equals("sam")).isTrue();
			
			for (int idx = 3; idx < 1000; idx++) {
				client.execute("insert into duptest (id, name, color ) values (?, ?, 'red')", new GUID(), "sam" + idx);
			}
			
			try {
				client.execute("insert into duptest (id, name, color ) values (?, 'george', 'blue')", new GUID());
			} catch (DBRuntimeException exc) {
				Assertions.fail("hi key should not be there");
			}
			
		} catch (AssertionFailedError err) {
			throw err;
		} catch(Exception exc) {
			exc.printStackTrace();
			Assertions.fail(exc.getMessage());
		}
		
	}
}
