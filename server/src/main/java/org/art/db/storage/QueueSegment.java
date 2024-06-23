package org.art.db.storage;

import java.io.IOException;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.art.db.common.Configuration;
import org.art.db.common.DiscoveryHandler;
import org.art.db.common.Document;
import org.art.db.common.DocumentSet;
import org.art.db.common.Key;
import org.art.db.common.LogEntry.Operation;
import org.art.db.common.Scheduler;

/**
 * A specialized MemorySegment for queues.  QueueSegment adds a lease method,
 * overrides the remove method to actually remove the document, and adds some
 * logic to periodically rebuild the document map.
 * 
 * The rebuilding logic is necessary because queues have a high rate of removes,
 * and the underlying concurrent map code does not actually shrink the size of
 * the map.
 */
public class QueueSegment extends MemorySegment {
	
	static record Lease(String owner, long expiration) {
		
	}
	
	private ConcurrentMap<Key, Lease> leases = new ConcurrentHashMap<>();
	
	private final ScheduledFuture<?> timeoutHandler;
	
	/**
	 * The number of calls to remove().  Used to decide when to compact the documents map.
	 */
	private AtomicLong removeCount = new AtomicLong();
	
	/**
	 * A lock used to synchronize updates and compactions.  The lock is necessary
	 * so that we don't lose updates during compactions.
	 */
	private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
	private final ReentrantReadWriteLock.ReadLock readLock = lock.readLock();
	private final ReentrantReadWriteLock.WriteLock writeLock = lock.writeLock();
	
	
	public QueueSegment(String table, DiscoveryHandler discoveryHandler, Configuration config) throws Exception {
		super(table, discoveryHandler, config, false);
		
		this.timeoutHandler = Scheduler.DEFAULT.scheduleAtFixedRate(this::timeoutLeases, 10, 10, TimeUnit.SECONDS);
		
	}
	
	public void close() throws IOException {
		this.timeoutHandler.cancel(true);
		super.close();
	}
	
	
	public synchronized DocumentSet lease(String owner, long timeout) {
		DocumentSet result = new DocumentSet();
		Iterator<Map.Entry<Key, Document>> iterator = this.documents.entrySet().iterator();
		long expirationTime = System.currentTimeMillis() + (timeout == 0 ? 10000 : timeout);
		
		while (iterator.hasNext()) {
			Map.Entry<Key, Document> nextEntry = iterator.next();
			
			Key nextKey = nextEntry.getKey();
			Document nextValue = nextEntry.getValue();
			
			if (this.leases.get(nextKey) == null) {
				this.leases.put(nextKey, new Lease(owner, expirationTime));
				result.add(nextValue);
				break;
			}
		}
		
		return result;
	}
	

	public void insert(Key key, Document doc, boolean propagate, boolean log) throws Exception {
		this.readLock.lock();
		
		try {
			super.insert(key, doc, propagate, log);
		} finally {
			this.readLock.unlock();
		}
	}
	
	public void remove(Key key, boolean propagate, boolean log) throws Exception {
		if (this.documents.containsKey(key)) {
			this.readLock.lock();
			
			try {
				this.log(Operation.REMOVE, key, null, propagate, log);

				this.documents.remove(key);
			} finally {
				this.readLock.unlock();
			}
		}
	}
	
	public void update(Key key, Document doc, boolean propagate, boolean log) throws Exception {
		this.readLock.lock();
		
		try {
			super.update(key, doc, propagate, log);
		} finally {
			this.readLock.unlock();
		}
		
	}
	
	private void timeoutLeases() {
		this.readLock.lock();
		
		Iterator<Map.Entry<Key, Lease>> iterator = this.leases.entrySet().iterator();
		
		while (iterator.hasNext()) {
			Map.Entry<Key, Lease> entry = iterator.next();
			Key key = entry.getKey();
			Lease lease = entry.getValue();
			
			if (System.currentTimeMillis() > lease.expiration || this.documents.get(key) == null) {
				iterator.remove();
			}			
		}
		
		this.readLock.unlock();
		
		if (this.removeCount.get() > 10000) {
			this.writeLock.lock();
			this.documents = new ConcurrentSkipListMap<>(this.documents);
			this.writeLock.unlock();
		}
	}
	
	
}
