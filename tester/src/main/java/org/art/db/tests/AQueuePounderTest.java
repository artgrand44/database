package org.art.db.tests;

import java.util.concurrent.atomic.AtomicInteger;

import org.art.db.client.Client;
import org.art.db.common.Document;
import org.art.db.common.DocumentSet;
import org.art.db.common.GUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AQueuePounderTest extends BaseQueryPounder {

	private static final Logger logger = LoggerFactory.getLogger(AQueuePounderTest.class);
	
	private static int NITEMS = 10000;
	private static int NLOOPS = 100;
	private static int NTHREADS = 150;
	
	private AtomicInteger deleteNextId  = new AtomicInteger(2 * NITEMS);
	
	AtomicInteger totalFailures = new AtomicInteger();

	public AQueuePounderTest() {
		super("./queuepounder.properties", NITEMS, NTHREADS);
	}


	private void insert(Client client, int idx) throws Exception {
		String insert = "insert into queue (id, name, value) values (uid(), ?, ?)";
		
		client.execute(insert, "name" + idx, "value" + idx);
		
	}
	
	private DocumentSet lease(Client client, String owner) throws Exception {
		return client.executeQuery("select * from queue where lease(?)", owner);
	}
	
	@Override
	protected void loadDabase() throws Exception {
		Client client = TestRunner.getAdapter().getClusterClient("admin", "admin").batch(false);
		String drop = "drop table if exists queue";
		String create = "CREATE QUEUE TABLE queue (id GUID NOT NULL PRIMARY KEY, name varchar(255), value varchar(255))";
		
		client.execute(drop);
		client.execute(create);
		
		for (int idx = 0; idx < NITEMS; idx++) {
			this.insert(client, idx);
			
			if (idx % 1000 == 0) {
				logger.info("Inserted " + idx);
			}
		}
		
		logger.info("inserts complete");
	}
	
	protected void pound(int index, int mainLoop) {	
		super.waitForAllStarted();
		try {
			Client client = TestRunner.getAdapter().getClusterClient("admin", "admin");
			
			for (int loop = 0; loop < NLOOPS; loop++) {
				String owner = "art" + index;
				
				this.addRead();
				
				DocumentSet set = this.lease(client, owner);
				
				for (Document doc : set) {
					GUID guid = doc.get("id", GUID.class);
					
					if (guid == null) {
						logger.info("no guid " + set);
					}

				}
				
				for (Document doc : set) {
					GUID guid = doc.get("id", GUID.class);
					
					
					
					if (!this.remove(client, guid)) {
						logger.info("remove failure");
					}
					
					this.addDelete();
					
					this.insert(client, -(this.deleteNextId.incrementAndGet()));
					this.addWrite();					
				}							
			}
		} catch (InterruptedException exc) {
			
		} catch (Throwable exc) {
			logger.error(exc.getMessage());
			
			exc.printStackTrace();
			this.addError(exc);
		}
		
		this.addCompletion();
	}
	

	private boolean remove(Client client, GUID guid) throws Exception {
		String delete = "delete from queue where id = ? returning *";
		DocumentSet set = client.executeQuery(delete, guid);
		
		if (set.size() != 1) {
			logger.info("bad remove " + set.size());
		}
		return set.size() == 1;
				
	}

	
}
