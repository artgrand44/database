package org.art.db.tests;

import java.net.ConnectException;
import java.net.URI;

import org.art.db.client.Client;
import org.art.db.common.ConnectionInfo;
import org.art.db.common.Document;
import org.art.db.common.DocumentSet;
import org.art.db.common.RestClient;
import org.art.db.common.RestException;
import org.art.db.common.Utils;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BackupTest extends BaseIntegrationTest {
	private static final Logger logger = LoggerFactory.getLogger(BackupTest.class);
	
	private static int NITEMS = 100;
	
	private RestClient restClient = new RestClient("admin", "admin");
	
	@Test
	public void test() {
		try {
			
			URI uri = TestAdapter.getInstance().startPrimary("./backup.properties");
			
			this.checkStarted(uri);
			restClient.get(restClient.buildURI(uri, "/db/reset?backup=true"));
			
			Client client = TestAdapter.getInstance().getClusterClient("admin", "admin").batch(false);
			String drop = "drop table if exists pounder";
			String create = "CREATE TABLE pounder (  ID int NOT NULL PRIMARY KEY, LastName varchar(255) NOT NULL default 'art', FirstName varchar(255), Age int)";
			String createIndex = "CREATE INDEX firstname ON pounder(FirstName)";
			
			client.execute(drop);
			client.execute(create);
			client.execute(createIndex);
			
			for (int idx = 0; idx < NITEMS; idx++) {
				this.insert(client, idx);
				
				if (idx % 1000 == 0) {
					logger.info("Inserted " + idx);
				}
			}
			
			logger.info("inserts complete");
			
			ConnectionInfo primary = new ConnectionInfo(TestAdapter.getInstance().getPrimaryURI().getHost(), "8080", "averlongshardname", "db2");
			
			
			restClient.get(restClient.buildURI(primary.toURI(), "/db/backup"));
			restClient.get(restClient.buildURI(uri, "/db/reset?backup=false"));
			
			TestAdapter.getInstance().stopPrimary();
			
			uri = TestAdapter.getInstance().startPrimary("./backup.properties");
			this.checkStarted(uri);
			
			for (int idx = 0; idx < NITEMS; idx++) {
				DocumentSet set = client.executeQuery("select * from pounder where ID = ?", this.getId(idx));
				
				Assertions.assertThat(set.size() == 1).isTrue();
				
				Document doc = set.iterator().next();
				Assertions.assertThat(doc.get("ID", Integer.class).equals(this.getId(idx))).isTrue();	
				
				set = client.executeQuery("select * from pounder where FirstName = ?", this.getName(idx));
				
				Assertions.assertThat(set.size() == 1).isTrue();
				
				doc = set.iterator().next();
				Assertions.assertThat(doc.get("FirstName", String.class).equals(this.getName(idx))).isTrue();				
			}
			
			
			
		} catch (AssertionFailedError err) {
			throw err;
		} catch(Throwable exc) {
			exc.printStackTrace();
			Assertions.fail(exc.getMessage());
		} 
		
	}
	
	private void checkStarted(URI uri) {
		boolean success = false;
		
		for (int idx = 0; idx < 10 && !success; idx++) {
			try {
				restClient.get(restClient.buildURI(uri, "/db/wait?count=%s", 1));
				success = true;
			} catch (RestException exc) {
				if (exc.getCause() instanceof ConnectException) {
					Utils.sleepSafely(2000);
				}
			} catch (Exception exc) {
				break;
			}
		}
		
		Assertions.assertThat(success).withFailMessage("Unable to contact server").isTrue();
		
	}
	private String getName(int idx) {
		return "Name" + idx;
	}
	
	private int getId(int idx) {
		return idx;
	}
	
	private void insert(Client client, int idx) throws Exception {
		String insert = "insert into pounder (ID, FirstName, LastName, Age) values (?, ?, 'grand', '1')";
		
		try {
			client.execute(insert, getId(idx), getName(idx));	
		} catch (Exception exc) {
			logger.info("exception on " + idx);
		}
	}

}
