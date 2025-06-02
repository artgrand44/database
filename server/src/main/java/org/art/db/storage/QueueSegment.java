package org.art.db.storage;

import java.io.IOException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
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
	
	class QueueEntry {
		final Document doc;
		final int priority;
		Lease lease;
		
		QueueEntry(Document doc, int priority) {
			this.doc = doc;
			this.priority = priority;
		}
	}
	
	
	private ConcurrentLinkedDeque<QueueEntry> leases = new ConcurrentLinkedDeque<>();
	
	/**
	 * Spin lock for the entry cache
	 */
	private AtomicBoolean lock = new AtomicBoolean();
	
	/**
	 * The queue entries
	 */
	private final LinkedHashMap<Key, QueueEntry> entries = new LinkedHashMap<Key, QueueSegment.QueueEntry>(10000, .75f);
	
	private final ScheduledFuture<?> timeoutHandler;
	
	/**
	 * The number of documents in the segment
	 */
	private AtomicLong documentCount = new AtomicLong();
	
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
		this.lock();
		this.entries.clear();
		this.unlock();
		this.leases.clear();
		
		System.gc();
	}
	
	@Override
	public Document get(Key key) throws Exception {
		QueueEntry entry;
		
		this.lock();
		entry = this.entries.get(key);
		this.unlock();
				
		return entry == null ? null : entry.doc;
	}
	
	public DocumentSet getAll() {
		DocumentSet result = new DocumentSet();
		
		this.lock();
		this.entries.values().forEach(x -> result.add(x.doc));
		this.unlock();
		
		return result;
	}
	
	
	
	@Override
	public long getDocumentCount() {
		return this.documentCount.get();
	}
	
	private int getPriority(Key key) {
		int instanceCount = this.discoveryHandler.getLocalInstanceCount();
		int localHash = this.discoveryHandler.getLocalInstance().hashCode() % instanceCount;
		
		return key.hashCode() % instanceCount == localHash ? 1 : 0;
		
	}

	public DocumentSet getLeased() {
		DocumentSet result = new DocumentSet();
		
		this.lock();
		this.entries.values().stream().filter(x -> x.lease != null).forEach(x -> result.add(x.doc));
		this.unlock();
		
		return result;
	}
	
	public DocumentSet getLeasedBy(String owner) {
		DocumentSet result = new DocumentSet();
		
		this.lock();
		this.entries.values().stream().filter(x -> x.lease != null && x.lease.owner().equals(owner)).forEach(x -> result.add(x.doc));
		this.unlock();
		
		return result;
	}
	
	public DocumentSet getUnleased() {
		DocumentSet result = new DocumentSet();
		
		this.lock();
		this.entries.values().stream().filter(x -> x.lease == null).forEach(x -> result.add(x.doc));
		this.unlock();
		
		return result;
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
		this.lock();
		
		for (int priority = 1; priority >= 0 && result.size() < 5; priority--) {
			Iterator<Map.Entry<Key, QueueEntry>> iterator = this.entries.entrySet().iterator();
			
			while (iterator.hasNext() && result.size() < 25) {
				Map.Entry<Key, QueueEntry> entry = iterator.next();
				QueueEntry qentry = entry.getValue();
				
				if (qentry.priority <= priority &&  qentry.lease == null) {
					qentry.lease = lease;
					this.leases.add(qentry);
					result.add(qentry.doc);
				}			
			}			
		}
		
		this.unlock();
		return result;
	}
	
	public DocumentSet leaseByKey(Key key, String owner, long timeout) {
		DocumentSet result = new DocumentSet();
		this.lock();
		
		QueueEntry entry = this.entries.get(key);
		
		if (entry != null && entry.lease == null) {
			entry.lease = new Lease(owner, System.currentTimeMillis() + (timeout == 0 ? 10000 : timeout));;
			this.leases.add(entry);
			result.add(entry.doc);
		}
		
		this.unlock();
		return result;
	}
	
	private void lock() {
		while (!this.lock.compareAndSet(false, true)) {
			Thread.onSpinWait();
		}
	}
	
	public void insert(Key key, Document doc, boolean propagate, boolean log) throws Exception {
		this.log(Operation.INSERT,  key, doc, propagate, log);
		
		this.lock();
		this.entries.put(key, new QueueEntry(doc, this.getPriority(key)));
		this.unlock();
		
		this.documentCount.incrementAndGet();
	}
		
	public void merge(QueueSegment prior) throws Exception {
		LogOutputStream.startBatch();
		
		this.lock();
		
		for (Map.Entry<Key, QueueEntry> pair : prior.entries.entrySet()) {
			Key key = pair.getKey();
			QueueEntry entry = pair.getValue();
			
			this.log(Operation.INSERT,  key, entry.doc, false, true);
			this.entries.put(key, entry);
		}
		
		this.unlock();
		LogOutputStream.endBatch();
	}
	
	public void remove(Key key, boolean propagate, boolean log) throws Exception {
		this.log(Operation.REMOVE, key, null, propagate, log);
		
		this.lock();
		this.entries.remove(key);
		this.unlock();
		this.documentCount.decrementAndGet();
	}
	
	public DocumentSet renewLease(Key key, String owner, long timeout) {
		DocumentSet result = new DocumentSet();
		QueueEntry entry;
		
		this.lock();
		entry = this.entries.get(key);
		this.unlock();
		
		if (entry == null) {
			return result;
		}
		
		if ((entry.lease == null || entry.lease.owner.equals(owner))) {
			entry.lease = new Lease(owner, System.currentTimeMillis() + (timeout == 0 ? 10000 : timeout));
			result.add(entry.doc);
		}
					
		return result;
	}
	
	private void unlock() {
		this.lock.set(false);
	}
	
	public void update(Key key, Document doc, boolean propagate, boolean log) throws Exception {
		throw new DBRuntimeException("Queue entries cannot be updated");		
	}
	
	private void timeoutLeases() {
		this.lock();
		
		Iterator<QueueEntry> iterator = this.leases.iterator();
		
		while (iterator.hasNext()) {
			QueueEntry entry = iterator.next();
			Lease lease;
			
			if (entry != null && (lease = entry.lease) != null && System.currentTimeMillis() > lease.expiration) {
				entry.lease = null;
				iterator.remove();
			}			
		}
		
		this.unlock();
	}
		
}
