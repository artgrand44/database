package org.art.storage;

import java.io.File;
import java.time.Duration;

import org.art.db.client.Client;
import org.art.db.common.DBRuntimeException;
import org.art.db.common.DocumentSet;
import org.art.db.common.RestClient;
import org.art.db.common.Router;
import org.art.db.common.Utils;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BuildIndexTest extends BaseIntegrationTest implements Runnable{
	
	private static final Logger logger = LoggerFactory.getLogger(BuildIndexTest.class);
	
	private volatile boolean doUpdates = true;
	private volatile int extraCount = 0;
	
	
	@Test
	public void test() {
		/*try {
			startInternal("./application.properties");
			
			Client client = this.getClient().batch(false);
			RestClient restClient = new RestClient("admin", "admin");
			
			String create = "CREATE TABLE builder (  ID int NOT NULL PRIMARY KEY, LastName varchar(255) NOT NULL default 'art', FirstName varchar(255), Age int)";
			String createIndex = "CREATE INDEX buildme ON builder(FirstName)";
			String dropIfExists = "DROP table if exists builder";
			String insert = "insert into builder (ID, LastName, FirstName,  Age) values (?, 'grand', 'art', 10)";
					
			client.execute(dropIfExists);
			client.execute(create);
			
			int count = 0;
			File externalDir = new File("./dbroot/SYS.SYS.builder");
			
			for (int idx = 0; ; idx++) {
				client.execute(insert, idx);
				
				if (idx % 10 == 0) {
					logger.info("send flush");
					restClient.get(restClient.buildURI(this.getLocalURI("8080"), "/db/flush?table=SYS.SYS.builder"));
					logger.info("after send flush");
				}
				count++;
								
				File[] segments = externalDir.listFiles((dir, name) -> name.endsWith(".seg"));
				
				if (segments != null && segments.length > 1) {
					break;
				}
				
				if (idx % 1000 == 0) {
					logger.info("idx " + idx);
				}
			}
			
			try (Client xclient = new Client(getLocalURI("8080"), "*", "admin", "admin").batch(false)){
				logger.info("*******got connection " + this.doUpdates);
				String update = "update builder set FirstName='sam' where ID=?";
				
				
				for (int idx = 0; idx < 100; idx++) {
					xclient.execute(update, idx);
					logger.info("did updates");
					this.extraCount++;
				}
			} catch(Throwable exc) {
				logger.info("*******got exception " + exc.getMessage());
				exc.printStackTrace();
				Assertions.fail(exc.getMessage());
			} 
			
			client.execute(createIndex);
			logger.info("after create index");
			
			boolean found = false;
			
			for (int idx = 0; idx < 1000 && !found; idx++) {
				try {
					DocumentSet art = client.executeQuery("select * from builder where FirstName = 'art'");
					
					if (art.isEmpty()) {
						Utils.sleepSafely(1000);
					} else {
						found = true;
						logger.info("found");
						break;
					}					
				} catch (DBRuntimeException exc) {
					logger.info("Got exception " + exc.getMessage());
					if (exc.getMessage().equals("The query does must include one of more indexed fields")) {
						Utils.sleepSafely(1000);
					}
				}
			}	
			
			Assertions.assertThat(found).isTrue();
		} catch (AssertionFailedError err) {
			throw err;
		} catch(Exception exc) {
			exc.printStackTrace();
			Assertions.fail(exc.getMessage());
		}*/
	}
	
	public void run() {
		logger.info("*******in background job");
		Router.trace = true;
		try (Client client = new Client(getLocalURI("8081"), "*", "admin", "admin")){
			logger.info("*******got connection " + this.doUpdates);
			String update = "update builder set FirstName='sam' where ID=?";
			
			
			for (int idx = 0; this.doUpdates; idx++) {
				client.execute(update, idx);
				logger.info("did updates");
				this.extraCount++;
			}
		} catch(Throwable exc) {
			logger.info("*******got exception " + exc.getMessage());
			exc.printStackTrace();
			Assertions.fail(exc.getMessage());
		} 
		
		logger.info("***********Background exiting");
	}


}
