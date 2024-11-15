package org.art.db.tests;

import org.art.db.client.Client;
import org.art.db.common.Document;
import org.art.db.common.DocumentSet;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GroupByTests extends BaseIntegrationTest {

	private static final Logger logger = LoggerFactory.getLogger(GroupByTests.class);
	
	
	private void insert(Client client, int idx) throws Exception {
		String insert = "insert into groups (id, country, state, value) values (?, ?, ?, ?)";
		String state = "state" + (idx % 4);
			
		client.execute(insert, idx, "us", state, idx);
		
	}
	
	
	private void loadDabase() throws Exception {
		Client client = TestAdapter.getInstance().getClusterClient("admin", "admin").batch(false);
		String drop = "drop table if exists grous";
		String create = "CREATE TABLE groups (id int NOT NULL PRIMARY KEY, country varchar(255), state varchar(255), value int)";
		String createIndex = "CREATE INDEX state ON groups(country)";
		
		
		client.execute(drop);
		client.execute(create);
		client.execute(createIndex);
		
		for (int idx = 0; idx < 100; idx++) {
			this.insert(client, idx);
			
			if (idx % 1000 == 0) {
				logger.info("Inserted " + idx);
			}
		}
		
		logger.info("inserts complete");
	}
	
	
	

	@Test
	public void test() {

		try  {
			TestAdapter.getInstance().startPrimary("./application.properties");
			
			this.loadDabase();

			Client client = TestAdapter.getInstance().getClusterClient("admin", "admin").batch(false);

			
			try {
				DocumentSet set = client.executeQuery("select state, count(id) c from groups where country = 'us' group by state");
				
				Assertions.assertThat(set.size() == 4).withFailMessage("Wrong size " + set.size()).isTrue();
				
				for (Document doc : set) {
					Assertions.assertThat(doc.get("state") != null).withFailMessage("Null state " + doc).isTrue();
					Assertions.assertThat(doc.get("state", String.class).startsWith("state")).withFailMessage("Bad state " + doc).isTrue();
					Assertions.assertThat(doc.get("c") != null).withFailMessage("Null count " + doc).isTrue();
					Assertions.assertThat(doc.get("c", Long.class) == 25).withFailMessage("Bad count " + doc).isTrue();
				}
				
				set = client.executeQuery("select state, count(id) c from groups where country = 'us' group by state having state='state1' or state='state3'");
				
				Assertions.assertThat(set.size() == 2).withFailMessage("Wrong size for having" + set.size()).isTrue();
				
				for (Document doc : set) {
					Assertions.assertThat(doc.get("state") != null).withFailMessage("Null state for having" + doc).isTrue();
					Assertions.assertThat(doc.get("state", String.class).startsWith("state")).withFailMessage("Bad state for having " + doc).isTrue();
					Assertions.assertThat(doc.get("c") != null).withFailMessage("Null count for having " + doc).isTrue();
					Assertions.assertThat(doc.get("c", Long.class) == 25).withFailMessage("Bad count for having " + doc).isTrue();
				}
				
				set = client.executeQuery("select state, count(id) c from groups where country = 'us' order by state desc group by state");
				
				String lastState = "zzzzzzzz";
				
				Assertions.assertThat(set.size() == 4).withFailMessage("Wrong size " + set.size()).isTrue();
				
				for (Document doc : set) {
					String state = doc.get("state", String.class);
					Assertions.assertThat(state.compareTo(lastState) < 0).withFailMessage("Bad sort last = " + lastState + " state = " + state).isTrue();
					lastState = state;
				}
				
			} catch (Exception exc) {
				Assertions.assertThat("The where clause is not supported for queries.".equals(exc.getMessage()));
			}
			
		} catch (AssertionFailedError err) {
			throw err;
		} catch(Throwable exc) {
			exc.printStackTrace();
			Assertions.fail(exc.getMessage());
		} 
	}
	
}
