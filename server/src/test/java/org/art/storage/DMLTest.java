package org.art.storage;

import org.art.db.common.Document;
import org.art.db.common.DocumentSet;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;

public class DMLTest extends BaseSQLTest {

	@Test
	public void test() {
		try {
			registerMetadata("SYS.SYS.dmltest", "id,first,last,count", "id", "name-N-first-last");
			
			String insert = "insert into dmltest (id, first, last, count) values ('1', 'art', 'grand', '1')";
			
			String update = "update dmltest set count='2' where id = '1'";
			String delete = "delete from dmltest where id = '1'";
			String query = "select * from dmltest where id = '1'";
			
			execute(insert);
			
			DocumentSet docs = executeSelect(query);
			
			Assertions.assertThat(docs.size() == 1).isTrue();
			
			Document first = docs.iterator().next();
			
			Assertions.assertThat("art".equals(first.get("first"))).isTrue();
			Assertions.assertThat("grand".equals(first.get("last"))).isTrue();
			
			execute(update);
			
			docs = executeSelect(query);
			
			Assertions.assertThat(docs.size() == 1).isTrue();
			
			first = docs.iterator().next();
			Assertions.assertThat("2".equals(first.get("count"))).isTrue();
			
			execute(delete);
			
			docs = executeSelect(query);
			
			Assertions.assertThat(docs.size() == 0).isTrue();
		} catch (AssertionFailedError err) {
			throw err;
		} catch(Exception exc) {
			exc.printStackTrace();
			Assertions.fail(exc.getMessage());
		}
	}
	
	
}
