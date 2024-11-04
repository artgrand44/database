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
	
	static record QueueEntry(Document doc, AtomicReference<Lease> lease, boolean local) {
		
	}
	
	private ConcurrentLinkedDeque<QueueEntry> leases = new ConcurrentLinkedDeque<>();
	
	/**
	 * The queue entries
	 */
	private final Cache<Key, QueueEntry> entries = Caffeine.newBuilder().build();
	
	private final ScheduledFuture<?> timeoutHandler;
	
	
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
		QueueEntry entry = this.entries.getIfPresent(key);
		
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
		DocumentSet result = this.lease(owner, timeout, true);
		
		if (result.size() < 5) {
			result.addAll(this.lease(owner, timeout, false));
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
	private DocumentSet lease(String owner, long timeout, boolean localOnly) {
		Iterator<Map.Entry<Key, QueueEntry>> iterator = this.entries.asMap().entrySet().iterator();
		DocumentSet result = new DocumentSet();
		
		Lease lease = new Lease(owner, System.currentTimeMillis() + (timeout == 0 ? 10000 : timeout));
		
		while (iterator.hasNext() && result.size() < 25) {
			Map.Entry<Key, QueueEntry> entry = iterator.next();
			QueueEntry qentry = entry.getValue();
			
			if (localOnly && !qentry.local) {
				continue;
			}
			
			if (qentry.lease.compareAndSet(null, lease)) {
				this.leases.add(qentry);
				result.add(qentry.doc);
			}			
		}
		
		return result;
	}

	public void insert(Key key, Document doc, boolean propagate, boolean log) throws Exception {
		this.log(Operation.INSERT,  key, doc, propagate, log);
		this.entries.put(key, new QueueEntry(doc, new AtomicReference<>(), this.discoveryHandler.isLocal(key)));
	}
	
	public void remove(Key key, boolean propagate, boolean log) throws Exception {
		this.log(Operation.REMOVE, key, null, propagate, log);
		this.entries.invalidate(key);
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
