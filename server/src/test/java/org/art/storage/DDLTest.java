package org.art.storage;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;

public class DDLTest extends BaseSQLTest {

	@Test
	public void test() {
		try {
			String create = "CREATE TABLE ddltest (  ID int NOT NULL PRIMARY KEY, LastName varchar(255) NOT NULL default 'art', FirstName varchar(255), Age int)";
			String createIndex = "CREATE INDEX myindex ON ddltest(FirstName)";
			String dropTable = "DROP table ddltest";
			String dropIndex = "DROP INDEX myindex ON ddltest";
					
			execute(create);
			execute(createIndex);
			
			execute("alter table ddltest drop column Age");
			execute("alter table ddltest add column foo  varchar(255)");
			
			
			execute(dropIndex);
			execute(dropTable);
			
		} catch (AssertionFailedError err) {
			throw err;
		} catch(Exception exc) {
			exc.printStackTrace();
			Assertions.fail(exc.getMessage());
		}
	}
}