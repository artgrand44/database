package org.art.storage;

import java.util.Random;

import org.art.db.client.Client;
import org.art.db.common.DocumentSet;
import org.art.db.common.Document;
import org.assertj.core.api.Assertions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ROQueryPounderTest extends BaseQueryPounder {

	private static final Logger logger = LoggerFactory.getLogger(ROQueryPounderTest.class);
	
	private static int NITEMS = 10000;
	private static int NLOOPS = 100000;
	private static int NTHREADS = 150;
	
	public ROQueryPounderTest() {
		super("./bigcache.properties", NITEMS, NTHREADS);
	}

	private String getName(int idx) {
		return "Name" + idx;
	}
	
	private int getId(int idx) {
		return idx;
	}
	
	@Override
	protected void pound(int index, int mainLoop) {	
		try {
			super.waitForAllStarted();
			
			String idQuery = "select * from pounder where ID = ?";
			Random random = new Random(index);
			Client client = this.getClient();

			for (int loop = 0; loop < NLOOPS; loop++) {
				int idx = super.getRandom(random);
				int id = this.getId(idx);
				String name = this.getName(idx);
				
				DocumentSet ids = client.executeQuery(idQuery, id);
				
				if (ids.size() == 0) {
					logger.info("missing id " + id);
					
					synchronized (this) {
						ids = client.executeQuery(idQuery, id);
						logger.info("after 2nd " + ids.size());
					}
					
				}
				Assertions.assertThat(ids.size() == 1).isTrue();
				
				Document doc = ids.iterator().next();
				Object docId = doc.get("ID");
				
				if (!Long.valueOf(id).equals(docId)) {
					logger.info("Bad id doc has " + docId + " expected " + id + " " + doc);
					logger.info("");
				}
				
				Assertions.assertThat(ids.iterator().next().get("FirstName").equals(name)).isTrue();
				
				this.addRead(1);
			}
		} catch (InterruptedException exc) {
			
		} catch (Throwable exc) {
			logger.error(exc.getMessage());
			exc.printStackTrace();
			this.addError(exc);
		}
		
		
		this.addCompletion();
	}

		
	

	@Override
	protected void loadDabase() throws Exception {
		Client client = this.getClient().batch(false);
		String create = "CREATE TABLE pounder (  ID int NOT NULL PRIMARY KEY, LastName varchar(255) NOT NULL default 'art', FirstName varchar(255), Age int)";
		String createIndex = "CREATE INDEX firstname ON pounder(FirstName)";
		
		client.execute(create);
		client.execute(createIndex);
		
		for (int idx = 0; idx < NITEMS; idx++) {
			this.insert(client, idx);
			
			if (idx % 1000 == 0) {
				logger.info("Inserted " + idx);
			}
		}
		
		logger.info("inserts complete");
		
	}

	private void insert(Client client, int idx) throws Exception {
		String insert = "insert into pounder (ID, FirstName, LastName, Age) values (?, ?, 'grand', '1')";
		
		client.execute(insert, getId(idx), getName(idx));
		
	}
	
}
