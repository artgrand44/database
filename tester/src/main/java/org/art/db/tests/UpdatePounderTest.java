package org.art.db.tests;

import java.util.Random;

import org.art.db.client.Batch;
import org.art.db.client.Client;
import org.art.db.common.Document;
import org.art.db.common.DocumentSet;
import org.assertj.core.api.Assertions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class UpdatePounderTest extends BaseQueryPounder {

	private static final Logger logger = LoggerFactory.getLogger(UpdatePounderTest.class);
	
	private static int NITEMS = 10000;
	private static int NLOOPS = 10000;
	private static int NUPDATERS = 100;
	private static int NTHREADS = 150 + NUPDATERS;
	private static int DOCS_PER_UPDATER = 1000;
	
	public UpdatePounderTest() {
		super("./bigcache.properties", NITEMS, NTHREADS);		
	}

	private String getName(int idx) {
		return idx + "Name";
	}
	
	private int getId(int idx) {
		return idx;
	}
	
	
	@Override
	protected void pound(int index, int mainLoop) {	
		super.waitForAllStarted();
		
		if (index < NUPDATERS) {
			this.doUpdates(index);
		} else {
			this.doSelects(index);
		}
	}
	
	protected void doSelects(int index) {	
		try {
			String nameQuery = "select * from pounder where FirstName = ?";
			String idQuery = "select * from pounder where ID = ?";
			String update = "update pounder set Age = '2' where ID = ? limit 1";
			Random random = new Random(index);
			Client client = TestAdapter.getInstance().getClusterClient("admin", "admin");

			for (int loop = 0; loop < NLOOPS; loop++) {
				int idx = super.getRandom(random);
				int id = this.getId(idx);
				String name = this.getName(idx);
				
				DocumentSet ids = client.executeQuery(idQuery, id);
				DocumentSet names = client.executeQuery(nameQuery, name);
				
				this.addRead();
				this.addRead();
				
				Assertions.assertThat(ids.size() == 1).isTrue();
				Assertions.assertThat(ids.iterator().next().get("ID").equals(Long.valueOf(id))).isTrue();
				
				if (names.size() != 1) {
					logger.info("Names is bad " + name);				
				}
				
				Assertions.assertThat(names.size() == 1).isTrue();
				
				Document doc = ids.iterator().next();
				
				Assertions.assertThat(doc.get("FirstName").equals(name)).isTrue();
				
				if (loop % 4  == 0) {
					client.execute(update, index);
					this.addWrite();
				}
			}
		} catch (InterruptedException exc) {
			
		} catch (Throwable exc) {
			logger.error(exc.getMessage());
			this.addError(exc);
		}
		
		this.addCompletion();
	}

	protected void doUpdates(int index) {	
		try {

			Client client = TestAdapter.getInstance().getClusterClient("admin", "admin");
			
			for (int loop = 0; loop < NLOOPS; loop++) {
				
				int key = NITEMS + (index * DOCS_PER_UPDATER);
				
				this.remove(client, key);
				this.addDelete();
				
				this.insert(client, key);
				this.addWrite();
			}
		} catch (InterruptedException exc) {

		} catch (Throwable exc) {
			logger.error(exc.getMessage());
			this.addError(exc);
		}
		
		this.addCompletion();
	}
		
	

	@Override
	protected void loadDabase() throws Exception {
		Client client = TestAdapter.getInstance().getClusterClient("admin", "admin").batch(false);
		String drop = "drop table if exists pounder";
		String create = "CREATE TABLE pounder (  ID int NOT NULL PRIMARY KEY, LastName varchar(255) NOT NULL default 'art', FirstName varchar(255), Age int)";
		String createIndex = "CREATE INDEX firstname ON pounder(FirstName)";
		String insert = "insert into pounder (ID, FirstName, LastName, Age) values (?, ?, 'grand', '1')";
		
		client.execute(drop);
		client.execute(create);
		client.execute(createIndex);
		
		Batch batch = client.createBatch();
		
		for (int idx = 0; idx < NITEMS; idx++) {
			batch.add(insert, getId(idx), getName(idx));
		}
		
		client.executeBatch(batch);
		
		for (int idx = 0; idx < NUPDATERS; idx++) {
			batch = client.createBatch();
			
			for (int jdx = 0; jdx < DOCS_PER_UPDATER; jdx++) {
				int key = NITEMS + (idx * DOCS_PER_UPDATER) + jdx;
				
				batch.add(insert, getId(key), getName(key));
			}
		}
		
		logger.info("inserts complete");
	}

	private void insert(Client client, int idx) throws Exception {
		String insert = "insert into pounder (ID, FirstName, LastName, Age) values (?, ?, 'grand', '1')";
		
		try {
			client.execute(insert, getId(idx), getName(idx));	
		} catch (Exception exc) {
			logger.info("exception on " + idx);
		}
	}
	
	private void remove(Client client, int idx) throws Exception {
		String delete = "delete from pounder where ID = ?";
		
		client.execute(delete, getId(idx));
		
	}
}
