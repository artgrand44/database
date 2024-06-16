package org.art.storage;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.art.db.client.Client;
import org.art.db.common.Document;
import org.art.db.common.DocumentSet;
import org.art.db.common.GUID;
import org.assertj.core.api.Assertions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AQueuePounderTest extends BaseQueryPounder {

	private static final Logger logger = LoggerFactory.getLogger(AQueuePounderTest.class);
	
	private static int NITEMS = 10000;
	private static int NLOOPS = 100000;
	private static int NTHREADS = 150;
	
	private AtomicInteger deleteNextId  = new AtomicInteger(2 * NITEMS);
	
	AtomicInteger totalFailures = new AtomicInteger();

	private ConcurrentHashMap<GUID, Long> released = new ConcurrentHashMap<GUID, Long>();
	
	private ConcurrentHashMap<GUID, String> leased = new ConcurrentHashMap<>();
	public AQueuePounderTest() {
		super("./bigcache.properties", NITEMS, NTHREADS);
	}


	private void insert(Client client, int idx) throws Exception {
		String insert = "insert into queue (id, name, value) values (UID(), ?, ?)";
		
		client.execute(insert, "name" + idx, "vaue" + idx);
		
	}
	
	private DocumentSet lease(Client client, String owner) throws Exception {
		return client.executeQuery("select * from queue where lease(?, 5)", owner);
	}
	
	@Override
	protected void loadDabase() throws Exception {
		Client client = new Client("cluster", "admin", "admin").batch(false);
		String create = "CREATE QUEUE TABLE queue (id guid NOT NULL PRIMARY KEY, name varchar(255), value varchar(255))";
		
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
			Client client = new Client("cluster", "admin", "admin");
			Set<GUID> threadReleased = new HashSet<>();
			
			for (int loop = 0; loop < NLOOPS; loop++) {
				String owner = "art" + index;
				
				this.addRead();
				
				DocumentSet set = this.lease(client, owner);
				Set<GUID> keys = new HashSet<>();
				
				for (Document doc : set) {
					GUID guid = doc.get("id", GUID.class);
					
					if (guid == null) {
						logger.info("no guid " + set);
					}

					if (keys.contains(guid)) {
						logger.info("duplicate key " + guid + " " + set);
					}
					
					if (leased.containsKey(guid)) {
						logger.info("duplicated lease guid " + guid + " owner "  + owner + " " + leased);
					}
					
					Assertions.assertThat(!leased.containsKey(guid)).isTrue();
					
					leased.put(guid, owner);
					Assertions.assertThat(!keys.contains(guid)).isTrue();
					keys.add(guid);
				}
				
				for (Document doc : set) {
					GUID guid = doc.get("id", GUID.class);
					
					Assertions.assertThat(!threadReleased.contains(guid)).isTrue();
					
					if (released.contains(guid)) {
						logger.info("guid " + guid + " already released by "  + released.get(guid) + " current thread = "  + Thread.currentThread().threadId());
						logger.info("");
					}
					Assertions.assertThat(!released.containsKey(guid)).isTrue();
					
					leased.remove(guid);
					
					if (!this.remove(client, guid)) {
						logger.info("remove failure");
					}
					
					released.put(guid, Thread.currentThread().threadId());
					threadReleased.add(guid);
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
	

	private boolean remove(Client client,GUID guid) throws Exception {
		String delete = "delete from queue where id = ? returning *";
		DocumentSet set = client.executeQuery(delete, guid);
		
		if (set.size() != 1) {
			logger.info("bad remove " + set.size());
		}
		return set.size() == 1;
				
	}

	
}
