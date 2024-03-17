package org.art.storage;

import org.art.db.common.ServerDocumentSet;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;

public class ColumnTest extends BaseSQLTest {

	@Test
	public void test() {
		try {
			String create = "CREATE TABLE coltest (  ID int NOT NULL PRIMARY KEY, FirstName varchar(255) default 'art', LastName varchar(255) NOT NULL , COUNT int)";
			String createIndex = "CREATE INDEX coltestindex ON coltest (FirstName)";
					
			execute(create);
			execute(createIndex);
			
			execute("insert into coltest (ID, FirstName, LastName, COUNT) values ('1', 'noah', 'grand', '1')");
			execute("insert into coltest (ID, LastName, COUNT) values ('2', 'grand', '1')");
			
			try {
				execute("insert into coltest (ID, FirstName, LastName, COUNT) values ('3', 'art', null, '1')");
				Assertions.fail("null not enforced on insert");
			} catch (Exception exc) {
				
			}
			
			ServerDocumentSet set = executeSelect("select * from coltest where ID = 1");
			
			Assertions.assertThat(set.iterator().next().get("FirstName").equals("noah")).isTrue();
			
			set = executeSelect("select * from coltest where ID = 2");
			
			Assertions.assertThat(set.iterator().next().get("FirstName").equals("art")).isTrue();
			
			execute("update coltest set COUNT='3' where ID = '1'");
			
			try {
				execute("update coltest set lastName=null where ID = '1'");
			} catch (Exception exc) {
				
			}
			
		} catch (AssertionFailedError err) {
			throw err;
		} catch(Exception exc) {
			exc.printStackTrace();
			Assertions.fail(exc.getMessage());
		}
	}
}