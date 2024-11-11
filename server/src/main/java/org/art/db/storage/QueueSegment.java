package org.art.db.storage;

import java.io.IOException;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.art.db.common.DBRuntimeException;
import org.art.db.common.DiscoveryHandler;
import org.art.db.common.Document;
import org.art.db.common.DocumentSet;
import org.art.db.common.Key;
import org.art.db.common.LogEntry.Operation;
import org.art.db.common.Scheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

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
	
	@SuppressWarnings("unused")
	private static final Logger logger = LoggerFactory.getLogger(QueueSegment.class);
	
	static record Lease(String owner, long expiration) {
		
	}
	
	static record QueueEntry(Document doc, AtomicReference<Lease> lease, int priority) {
		
	}
	
	private ConcurrentLinkedDeque<QueueEntry> leases = new ConcurrentLinkedDeque<>();
	
	/**
	 * The queue entries
	 */
	private final Cache<Key, QueueEntry> entries = Caffeine.newBuilder().build();
	
	private final ScheduledFuture<?> timeoutHandler;
	
	
	public QueueSegment(String table, DiscoveryHandler discoveryHandler, Environment config) throws Exception {
		super(table, discoveryHandler, config);
		
		this.timeoutHandler = Scheduler.DEFAULT.scheduleAtFixedRate(this::timeoutLeases, 10, 10, TimeUnit.SECONDS);
	}
	
	public void close() throws IOException {
		this.timeoutHandler.cancel(true);
		super.close();
	}
	
	@Override
	public void deleteLog() {
		super.deleteLog();
		this.entries.invalidateAll();
		this.leases.clear();
		
		System.gc();
	}
	@Override
	public Document get(Key key) throws Exception {
		QueueEntry entry = this.entries.getIfPresent(key);
		
		return entry == null ? null : entry.doc;
	}
	
	private int getPriority(Key key) {
		int instanceCount = this.discoveryHandler.getLocalInstanceCount();
		int localHash = this.discoveryHandler.getLocalInstance().hashCode() % instanceCount;
		
		return key.hashCode() % instanceCount == localHash ? 1 : 0;
		
	}

	/**
	 * Find a set of documents that are not currently leased, create
	 * leases for them, and return them.  In order to reduce conflicts,
	 * we give priority to documents whose keys are associated with the 
	 * current shard.
	 * 
	 * @param owner The owner for the new lease
	 * @param timeout The timeout for the new lease
	 * @return
	 */
	public DocumentSet lease(String owner, long timeout) {
		DocumentSet result = new DocumentSet();
		Lease lease = new Lease(owner, System.currentTimeMillis() + (timeout == 0 ? 10000 : timeout));
		
		for (int priority = 1; priority >= 0 && result.size() < 5; priority--) {
			Iterator<Map.Entry<Key, QueueEntry>> iterator = this.entries.asMap().entrySet().iterator();
			
			while (iterator.hasNext() && result.size() < 25) {
				Map.Entry<Key, QueueEntry> entry = iterator.next();
				QueueEntry qentry = entry.getValue();
				
				if (qentry.priority <= priority &&  qentry.lease.compareAndSet(null, lease)) {
					this.leases.add(qentry);
					result.add(qentry.doc);
				}			
			}			
		}
		
		return result;
	}
	
	public void insert(Key key, Document doc, boolean propagate, boolean log) throws Exception {
		this.log(Operation.INSERT,  key, doc, propagate, log);
		this.entries.put(key, new QueueEntry(doc, new AtomicReference<>(), this.getPriority(key)));
	}
		
	public void merge(QueueSegment prior) throws Exception {
		LogOutputStream.startBatch();
		
		for (Map.Entry<Key, QueueEntry> pair : prior.entries.asMap().entrySet()) {
			Key key = pair.getKey();
			QueueEntry entry = pair.getValue();
			
			this.log(Operation.INSERT,  key, entry.doc, false, true);
			this.entries.put(key, entry);
		}
		
		LogOutputStream.endBatch();
	}
	
	public void remove(Key key, boolean propagate, boolean log) throws Exception {
		this.log(Operation.REMOVE, key, null, propagate, log);
		this.entries.invalidate(key);
	}
	
	public DocumentSet renewLease(Key key, String owner, long timeout) {
		QueueEntry entry = this.entries.getIfPresent(key);
		DocumentSet result = new DocumentSet();
		
		
		if (entry == null) {
			return result;
		}
		
		Lease oldLease = entry.lease.get();
		Lease newLease = new Lease(owner, System.currentTimeMillis() + (timeout == 0 ? 10000 : timeout));
		
		if ((oldLease == null || oldLease.owner.equals(owner)) && entry.lease.compareAndSet(oldLease, newLease)) {
			result.add(entry.doc);
		}
					
		return result;
	}
	
	public void update(Key key, Document doc, boolean propagate, boolean log) throws Exception {
		throw new DBRuntimeException("Queue entries cannot be updated");
		
	}
	
	private void timeoutLeases() {
		Iterator<QueueEntry> iterator = this.leases.iterator();
		
		while (iterator.hasNext()) {
			QueueEntry entry = iterator.next();
			Lease lease;
			
			if (entry != null && (lease = entry.lease.get()) != null && System.currentTimeMillis() > lease.expiration) {
				entry.lease.set(null);
				iterator.remove();
			}			
		}		
	}
		
}
