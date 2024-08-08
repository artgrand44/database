package org.art.db.tests;

import org.opentest4j.AssertionFailedError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.art.db.client.Client;
import org.art.db.common.DBRuntimeException;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

public class NegativeSQLTest extends BaseIntegrationTest {
	
	@SuppressWarnings("unused")
	private static final Logger logger = LoggerFactory.getLogger(NegativeSQLTest.class);
	
	private Client client;
	
	private void checkExecuteException(String statement, String message) {
		try {
			this.client.execute(statement);
			Assertions.fail(statement + " did not throw an exception");
		} catch (DBRuntimeException exc) {
			Assertions.assertThat(exc.getMessage().equals(message));
		} catch (Exception exc) {
			Assertions.fail(statement + " generated an exception of type " + exc.getClass().getSimpleName());
		}
	}
	
	private void checkQueryException(String statement, String message) {
		try {
			this.client.executeQuery(statement);
			Assertions.fail(statement + " did not throw an exception");
		} catch (AssertionFailedError err) {
			throw err;
		} catch (DBRuntimeException exc) {
			Assertions.assertThat(exc.getMessage().equals(message)).isTrue();
		} catch (Exception exc) {
			Assertions.fail(statement + " generated an exception of type " + exc.getClass().getSimpleName());
		}
	}
	@Test
	public void test() {
		
			try  {
				TestRunner.getAdapter().startPrimary("./application.properties");
				
				String drop = "drop table if exists negative";
				String create = "CREATE TABLE negative (id int NOT NULL PRIMARY KEY, first varchar(255), last varchar(255))";
				String createIndex = "CREATE INDEX negative ON negative(last)";
				String insert = "insert into negative (id, first, last ) values (?, 'art', ?)";
				String query = "select * from negative where last < 'last3000' order by last";
				String update = "update negative set last = 'foo' where id = 1";
				String delete = "delete from negative where id = 1";
				
				
				this.client = this.getClient().batch(false);
				
				this.client.execute(drop);
				
				this.checkExecuteException(insert, "Table SYS.SYS.negative does not exist.");
				this.checkExecuteException(update, "Table SYS.SYS.negative does not exist.");
				this.checkExecuteException(delete, "Table SYS.SYS.negative does not exist.");
				this.checkQueryException(query,    "Table SYS.SYS.negative does not exist.");
				
				this.client.execute(create);
				this.client.execute(createIndex);
				
				String badInsert = "insert into negative (id, junk, last ) values (?, 'art', ?)";
				String badQuery = "select * from negative where junk < 'last3000' order by last";
				String badUpdate = "update negative set junk = 'foo' where id = 1";
				String badDelete = "delete from negative where junk = 1";
				
				this.checkExecuteException(badInsert, "Table SYS.SYS.negative does not exist.");
				this.checkQueryException(badQuery,    "junk is not a valid column.");
				this.checkExecuteException(badUpdate, "junk is not a valid column.");
				this.checkExecuteException(badDelete, "junk is not a valid column.");
				this.checkExecuteException(query,     "Select statements must be called via Client.executeQuery.");
				
				String badSyntaxQuery = "select *  where junk < 'last3000' order by last";
				this.checkQueryException(badSyntaxQuery, "Syntax not supported.");
				
				
		} catch (AssertionFailedError err) {
			throw err;
		} catch (AssertionError exc) {
			throw exc;
		} catch(Throwable exc) {
			exc.printStackTrace();
			Assertions.fail(exc.getMessage());
		} 
	}

}

