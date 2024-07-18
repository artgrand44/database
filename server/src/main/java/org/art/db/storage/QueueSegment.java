package org.art.db.storage;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;

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
	
	static record QueueEntry(Document doc, AtomicReference<Lease> lease) {
		
	}
	private ConcurrentLinkedDeque<WeakReference<QueueEntry>> leases = new ConcurrentLinkedDeque<>();
	
	/**
	 * The queue entries
	 */
	private volatile ConcurrentSkipListMap<Key, QueueEntry> entries = new ConcurrentSkipListMap<>();
	
	
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
	
	
	public QueueSegment(String table, DiscoveryHandler discoveryHandler, Environment config) throws Exception {
		super(table, discoveryHandler, config, false);
		
		this.timeoutHandler = Scheduler.DEFAULT.scheduleAtFixedRate(this::timeoutLeases, 10, 10, TimeUnit.SECONDS);
		
	}
	
	public void close() throws IOException {
		this.timeoutHandler.cancel(true);
		super.close();
	}
	
	@Override
	public Document get(Key key) throws Exception {
		QueueEntry entry = this.entries.get(key);
		
		return entry == null ? null : entry.doc;
	}
	
	/**
	 * Find document that is not currently leased, obtain a lease
	 * for it, and return the document.
	 * 
	 * This method makes two passes over the documents.  During the
	 * first pass, it only examines documents whose keys have the same
	 * hash code as the current instance.  During the second pass, it
	 * looks at all of the documents.  This minimizes the chance that
	 * two instances will give out leases for the same document at the
	 * same time.
	 * 
	 * @param owner The owner for the new lease
	 * @param timeout The timeout for the new lease
	 * @return
	 */
	public DocumentSet lease(String owner, long timeout) {
		DocumentSet result = new DocumentSet();
		Document doc = this.lease(owner, timeout, true);
		
		if (doc == null) {
			doc = this.lease(owner, timeout, false);
		}
		
		if (doc != null) {
			result.add(doc);
		}
		
		return result;
	}
	
	/**
	 * Private method to obtain a lease.
	 * @param owner  The owner for the new lease
	 * @param timeout The timeout for the new lease
	 * @param localOnly True if we should only consider keys who's hash matches the local instance
	 * @return
	 */
	private Document lease(String owner, long timeout, boolean localOnly) {
		Iterator<Map.Entry<Key, QueueEntry>> iterator = this.entries.entrySet().iterator();
		
		Lease lease = new Lease(owner, System.currentTimeMillis() + (timeout == 0 ? 10000 : timeout));
		int instanceCount = this.discoveryHandler.getLocalInstanceCount();
		int localHash = this.discoveryHandler.getLocalInstance().hashCode() % instanceCount;
		
		while (iterator.hasNext()) {
			Map.Entry<Key, QueueEntry> entry = iterator.next();
			
			Key key = entry.getKey();
			QueueEntry qentry = entry.getValue();
			
			if (localOnly && key.hashCode() % instanceCount != localHash) {
				continue;
			}
			
			if (qentry.lease.compareAndSet(null, lease)) {
				this.leases.add(new WeakReference<>(qentry));
				return qentry.doc;
			}			
		}
		
		return null;
	}

	public void insert(Key key, Document doc, boolean propagate, boolean log) throws Exception {
		this.readLock.lock();
		
		try {
			this.log(Operation.INSERT,  key, doc, propagate, log);
			
			this.entries.put(key, new QueueEntry(doc, new AtomicReference<>()));
		} finally {
			this.readLock.unlock();
		}
	}
	
	public void remove(Key key, boolean propagate, boolean log) throws Exception {
		if (this.entries.containsKey(key)) {
			this.readLock.lock();
			
			try {
				this.log(Operation.REMOVE, key, null, propagate, log);

				this.entries.remove(key);
				this.removeCount.incrementAndGet();
			} finally {
				this.readLock.unlock();
			}
			

			if (this.removeCount.get() > 10000) {
				this.writeLock.lock();
				this.entries = new ConcurrentSkipListMap<>(this.entries);
				this.removeCount.set(0);
				this.writeLock.unlock();
			}
		}
	}
	
	public void update(Key key, Document doc, boolean propagate, boolean log) throws Exception {
		throw new DBRuntimeException("Queue entries cannot be updated");
		
	}
	
	private void timeoutLeases() {
		Iterator<WeakReference<QueueEntry>> iterator = this.leases.iterator();
		
		while (iterator.hasNext()) {
			QueueEntry entry = iterator.next().get();
			Lease lease;
			
			if (entry != null && (lease = entry.lease.get()) != null && System.currentTimeMillis() > lease.expiration) {
				entry.lease.set(null);
				iterator.remove();
			}			
		}
		
	}
	
	
}
