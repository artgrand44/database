package org.art.storage;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.art.db.client.Client;
import org.art.db.common.Document;
import org.art.db.common.DocumentSet;
import org.assertj.core.api.Assertions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AQueuePounderTest extends BaseQueryPounder {

	private static final Logger logger = LoggerFactory.getLogger(AQueuePounderTest.class);
	
	private static int NITEMS = 10000;
	private static int NLOOPS = 1000000;
	private static int NTHREADS = 150;
	
	private AtomicInteger deleteNextId  = new AtomicInteger(2 * NITEMS);
	
	AtomicInteger totalFailures = new AtomicInteger();

	private ConcurrentHashMap<Long, Long> released = new ConcurrentHashMap<>();
	
	private ConcurrentHashMap<Long, Long> leased = new ConcurrentHashMap<>();
	public AQueuePounderTest() {
		super("./bigcache.properties", NITEMS, NTHREADS);
	}


	private void insert(Client client, int idx) throws Exception {
		String insert = "insert into queue (id, name, value) values (?, ?, ?)";
		
		client.execute(insert, idx, "name" + idx, "vaue" + idx);
		
	}
	
	private DocumentSet lease(Client client, String owner) throws Exception {
		String lease = "update queue set owner=? where owner is null limit 1 returning *";
		
		DocumentSet result = client.executeQuery(lease, owner);
		
		for (Document doc : result) {
			if (!doc.get("owner").equals(owner)) {
				logger.info("wrong owner");
			}
			Assertions.assertThat(doc.get("owner").equals(owner)).isTrue();
		}
		
		return result;
		
	}
	@Override
	protected void loadDabase() throws Exception {
		Client client = new Client("cluster", "admin", "admin").batch(false);
		String create = "CREATE TABLE queue (id int NOT NULL PRIMARY KEY, name varchar(255), value varchar(255), owner varchar(255))";
		String createIndex = "CREATE INDEX index ON queue(owner)";
		
		
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
	protected void pound(int index, int mainLoop) {	
		super.waitForAllStarted();
		try {
			Client client = new Client("cluster", "admin", "admin");
			Set<Long> threadReleased = new HashSet<>();
			
			for (int loop = 0; loop < NLOOPS; loop++) {
				String owner = "art" + index;
				
				this.addRead();
				
				DocumentSet set = this.lease(client, owner);
				Set<Long> keys = new HashSet<>();
				
				for (Document doc : set) {
					Long guid = doc.get("id", Long.class);

					Assertions.assertThat(doc.get("owner").equals(owner)).isTrue();
					
					if (keys.contains(guid)) {
						logger.info("duplicate key " + guid + " " + set);
					}
					
					Assertions.assertThat(!leased.containsKey(guid)).isTrue();
					
					leased.put(guid, Thread.currentThread().threadId());
					Assertions.assertThat(!keys.contains(guid)).isTrue();
					keys.add(guid);
				}
				
				for (Document doc : set) {
					Long guid = doc.get("id", Long.class);
					
					Assertions.assertThat(doc.get("owner").equals(owner)).isTrue();
					Assertions.assertThat(guid != null).isTrue();
					Assertions.assertThat(!threadReleased.contains(guid)).isTrue();
					
					if (released.contains(guid)) {
						logger.info("guid " + guid + " already released by "  + released.get(guid) + " current thread = "  + Thread.currentThread().threadId());
						logger.info("");
					}
					Assertions.assertThat(!released.containsKey(guid)).isTrue();
					
					if (!this.remove(client, owner, guid)) {
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
	

	private boolean remove(Client client, String owner, Long guid) throws Exception {
		/*DocumentSet locked =  client.executeQuery("select * from queue where id = ? and owner = ?", guid, owner);
		DocumentSet all =  client.executeQuery("select * from queue where id > ?", GUID.MINIMUM);
		
		if (locked.size() != 1) {
			logger.info("wrong locked owner" + owner + " guid " + guid);
			logger.info("wrong locked " + locked);
			logger.info("all          " + all.size());
			logger.info("");
		}*/
		String delete = "delete from queue where id = ? and owner = ? returning *";
		DocumentSet set;
		
				
		set = client.executeQuery(delete, guid, owner);
		
		/*if (set.size() != 1) {
			
			DocumentSet other = client.executeQuery("select * from queue where id = ?", guid);
			
			logger.info("no remove " + owner + " " + set + " "+ other);
			logger.info("");;
		}*/
		
		if (set.size() != 1) {
			logger.info("bad remove " + set.size());
		}
		return set.size() == 1;
				
	}

	
}
