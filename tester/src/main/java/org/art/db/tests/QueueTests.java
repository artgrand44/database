package org.art.db.tests;

import org.art.db.client.Client;
import org.art.db.common.Document;
import org.art.db.common.DocumentSet;
import org.art.db.common.GUID;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class QueueTests extends BaseIntegrationTest {

	private static final Logger logger = LoggerFactory.getLogger(QueueTests.class);
	
	
	private void insert(Client client, int idx) throws Exception {
		String insert = "insert into queue (id, name, value) values (uid(), ?, ?)";
		
		client.execute(insert, "name" + idx, "value" + idx);
		
	}
	
	private DocumentSet lease(Client client, String owner) throws Exception {
		return client.executeQuery("select * from queue where lease(?)", owner);
	}
	
	private DocumentSet leaseByKey(Client client, GUID key, String owner) throws Exception {
		return client.executeQuery("select * from queue where leaseByKey(?, ?, ?)", owner, 1000, key);
	}
	
	private void loadDabase() throws Exception {
		Client client = TestAdapter.getInstance().getClusterClient("admin", "admin").batch(false);
		String drop = "drop table if exists queue";
		String create = "CREATE QUEUE TABLE queue (id GUID NOT NULL PRIMARY KEY, name varchar(255), value varchar(255))";
		
		client.execute(drop);
		client.execute(create);
		
		for (int idx = 0; idx < 100; idx++) {
			this.insert(client, idx);
		}
		
		logger.info("inserts complete");
	}
	
	
	private boolean remove(Client client, GUID guid) throws Exception {
		String delete = "delete from queue where id = ? returning *";
		DocumentSet set = client.executeQuery(delete, guid);
		
		if (set.size() != 1) {
			logger.info("bad remove " + set.size());
		}
		return set.size() == 1;
				
	}

	@Test
	public void test() {

		try  {
			TestAdapter.getInstance().startPrimary("./application.properties");
			
			this.loadDabase();

			Client client = TestAdapter.getInstance().getClusterClient("admin", "admin").batch(false);

			DocumentSet set = this.lease(client, "art");
			DocumentSet leased = client.executeQuery("select * from queue where isLeased()");
			DocumentSet unleased = client.executeQuery("select * from queue where isUnleased()");
			
			Assertions.assertThat(set.equals(leased)).isTrue();
			Assertions.assertThat(leased.size() + unleased.size() == 100).isTrue();
			
			GUID g = unleased.iterator().next().get("id", GUID.class);
			DocumentSet leasedby = this.leaseByKey(client, g, "art");
			Assertions.assertThat(leasedby.size() == 1).isTrue();
			Assertions.assertThat(this.remove(client, g)).isTrue();
			
			for (Document doc : set) {
				GUID guid = doc.get("id", GUID.class);

				DocumentSet other = client.executeQuery("select * from queue where id = ?", guid);

				Assertions.assertThat(other.size() == 1).isTrue();
				
				DocumentSet renewedSet = client.executeQuery("select * from queue where renewLease(?, 'art', 2000)", guid);
				Assertions.assertThat(renewedSet.size() == 1).isTrue();
				Assertions.assertThat(renewedSet.iterator().next().get("id").equals(guid)).isTrue();
				
				Assertions.assertThat(this.remove(client, guid)).isTrue();
			}	

			try {
				client.executeQuery("select * from queue where id = ? and lease(?)", 1, "art");
				Assertions.fail("should get exception");
			} catch (Exception exc) {
				Assertions.assertThat("The where clause is not supported for queries.".equals(exc.getMessage()));
			}
			
			DocumentSet counts = client.executeQuery("select count(*) from queue");
			Assertions.assertThat(counts.size() == 1).isTrue();
			Assertions.assertThat(counts.iterator().next().get("count") != null).isTrue();
			

		} catch (AssertionFailedError err) {
			throw err;
		} catch(Throwable exc) {
			exc.printStackTrace();
			Assertions.fail(exc.getMessage());
		} 
	}
	
}
