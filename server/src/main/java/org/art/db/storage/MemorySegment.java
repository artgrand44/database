package org.art.db.storage;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.art.db.common.Blob;
import org.art.db.common.Configuration;
import org.art.db.common.DiscoveryHandler;
import org.art.db.common.Document;
import org.art.db.common.DocumentSet;
import org.art.db.common.GUID;
import org.art.db.common.Key;
import org.art.db.common.LogEntry;
import org.art.db.common.LogEntry.Operation;
import org.art.db.common.RestClient;
import org.art.db.common.Scheduler;
import org.art.db.common.TypedOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An in-memory DataTable that is restored to its previous
 * state after a crash
 */

public class MemorySegment extends Segment {
	private static final Logger logger = LoggerFactory.getLogger(MemorySegment.class);
	
	static record Lease(String owner, long expiration) {
		
	}
	
	/**
	 * The log file
	 */
	private final File logFile;
	
	/**
	 * The log
	 */
	private final LogOutputStream log;
	
	/**
	 * The table name
	 */
	private final String table;
	
	private final boolean isQueue;
	
	/**
	 * Has the segment been closed
	 */
	private volatile boolean closed = false;
	
	/**
	 * The documents in this segment
	 */
	private volatile ConcurrentSkipListMap<Key, Document> documents = new ConcurrentSkipListMap<>();
	
	
	private final ConcurrentMap<Key, Lease> leases = new ConcurrentHashMap<>();
	
	/**
	 * The rest client for sending snapshots
	 */
	private final RestClient restClient = new RestClient();
	
	private static AtomicLong nextId = new AtomicLong();
	
	private final String id;

		
	/**
	 * The batches that need to be broadcast
	 */
	private final Deque<List<LogEntry>> broadcastQueue = new ConcurrentLinkedDeque<List<LogEntry>>();
	
	
	/**
	 * The current batch being build up
	 */
	private ConcurrentLinkedDeque<LogEntry> currentBroadcastBatch = new ConcurrentLinkedDeque<>();
	
	/**
	 * Has the segment been saved
	 */
	private boolean isSaved = false;
	
	/**
	 * The number of calls to remove().  Used to decide when to compact the documents map.
	 */
	private AtomicLong removeCount = new AtomicLong();
	
	
	/**
	 * The timer for async broadcasts
	 */
	private final ScheduledFuture<?> broadcastHandler;
	
	private final ScheduledFuture<?> timeoutHandler;
	
	/**
	 * Lock for queuing up broadcasts
	 */
	private final ReentrantLock broadcastLock = new ReentrantLock();
	
	private final ReentrantReadWriteLock documentsLock = new ReentrantReadWriteLock();
	
	/**
	 * Discovery handler for sending snapshots
	 */
	private final DiscoveryHandler discoveryHandler;
	
	private final long logDelay;
	
	
	private static ScheduledExecutorService SCHEDULER = Scheduler.createScheduler("MemSeg", 5);
	

	public MemorySegment(String table, DiscoveryHandler discoveryHandler, Configuration config, boolean isQueue) throws Exception {
		super();
		
		Interval interval;
		
		try {
			interval = new Interval(config.getProperty("org.art.db.logDelay", config.getDefault("org.art.db.logDelay")));
		} catch (Exception exc) {
			interval = new Interval("0s");
		}
		
		this.logFile = Files.getTableLog(table);
		this.log = new LogOutputStream(this.logFile);
		this.table = table;
		this.discoveryHandler = discoveryHandler;
		this.logDelay = interval.toMillis();
		this.isQueue = isQueue;
		this.broadcastHandler = logDelay == 0 ? null : SCHEDULER.scheduleAtFixedRate(this::broadcastTimer, 1,  1,  TimeUnit.SECONDS);
		this.id = "Mem" + nextId.incrementAndGet();		
		this.timeoutHandler = isQueue ? Scheduler.DEFAULT.scheduleAtFixedRate(this::timeoutLeases, 10, 10, TimeUnit.SECONDS) : null;
		
		this.setMetadata(Metadata.getMetadata(table));		
		
	}
	
	/**
	 * Fire an async broadcast
	 */
	private void broadcastTimer() {

		this.broadcastLock.lock();

		try {
			if (this.currentBroadcastBatch.size() != 0) {
				this.broadcastQueue.add(new ArrayList<>(this.currentBroadcastBatch));
				this.currentBroadcastBatch.clear();
			}
		} finally {
			this.broadcastLock.unlock();
		}
		
		Collection<URI> peers;
		List<LogEntry> batch = this.broadcastQueue.pollFirst();

		if (this.discoveryHandler == null || (peers = this.discoveryHandler.getLocalPeers()).isEmpty() || batch == null) {
			return;
		}

		try {
			try (TypedOutputStream stream = new TypedOutputStream()) {
				stream.writeUTF(this.table);

				for (LogEntry entry : batch) {
					if (entry.isPropagate()) {
						stream.writeObject(entry);
					}
				}

			logger.info("broadcast from " + this.discoveryHandler.getLocalPort() + " to " + peers);
			this.restClient.broadcast(peers, "/db/propagate", stream.toByteArray());			}
		} catch (Exception exc) {
			if (batch != null) {
				this.broadcastQueue.addFirst(batch);
			}
		}
				
	}
	
	public void close() throws IOException {
		if (this.closed) {
			new Exception().printStackTrace();
		}
		
		try {
			if (this.timeoutHandler != null) {
				this.timeoutHandler.cancel(true);
			}
			
			if (this.broadcastHandler != null) {
				this.broadcastHandler.cancel(true);
			}

			this.broadcastTimer();
			this.log.close();
			this.logFile.delete();
		} finally {
			this.closed = true;
		}
	}
	
	public void crash() throws IOException {
		this.log.close();
	}
	

	/**
	 * Log an update
	 * @param key
	 * @param value
	 * @throws Exception
	 */
	
	private void log(Operation op, Key key, Document value, boolean propagate, boolean log) throws Exception {

		LogEntry entry = new LogEntry(op, key, value, System.currentTimeMillis(), propagate);

		if (log) {
			this.log.write(entry);
		}

		if (propagate) {
			this.currentBroadcastBatch.add(entry);
		}
	
	}
	
	public void deleteLog() {
		this.logFile.delete();
	}
	
	@Override
	public Document get(Key key) throws Exception {
		this.documentsLock.readLock().lock();
		
		Document result = this.documents.get(key);
		
		this.documentsLock.readLock().unlock();
		
		return result;
	}

	public long getLogSize() {
		return this.log.getLength();
	}
	
	/**
	 * Intercept puts to log the update
	 */
	public void insert(Key key, Document doc, boolean propagate, boolean log) throws Exception {
		this.log(Operation.INSERT,  key, doc, propagate, log);
		
		this.documentsLock.readLock().lock();
		this.documents.put(key, doc);
		this.documentsLock.readLock().unlock();
		
		this.getBloomFilter().set(this.getHash(key));
		
		GUID guid;
		
		if (key.isGUID() && (guid = key.getGUID()).compareTo(this.getHighGUID()) > 0) {
			this.setHighGUID(guid);
		}
	}
	
	@Override
	public Iterator<Entry<Key, Blob>> iterator() {
		return new SortedIterator(this.documents);
	}
	
	public boolean isClosed() {
		return this.closed;
	}
	
	public synchronized DocumentSet lease(String owner, long timeout) {
		DocumentSet result = new DocumentSet();
		Iterator<Map.Entry<Key, Document>> iterator = this.documents.entrySet().iterator();
		long expirationTime = System.currentTimeMillis() + (timeout == 0 ? 10000 : timeout);
		
		while (iterator.hasNext()) {
			Map.Entry<Key, Document> nextEntry = iterator.next();
			
			Key nextKey = nextEntry.getKey();
			Document nextValue = nextEntry.getValue();
			
			if (this.leases.get(nextKey) == null && !nextValue.isZombie()) {
				this.leases.put(nextKey, new Lease(owner, expirationTime));
				result.add(nextValue);
				break;
			}
		}
		
		return result;
	}
	

	/**
	 * Intercept a remove to log the updates
	 */
	public void remove(Key key, boolean propagate, boolean log) throws Exception {
		this.documentsLock.readLock().lock();
		
		try {
			if (this.isQueue) {
				if (this.documents.containsKey(key)) {
					this.log(Operation.REMOVE, key, null, propagate, log);

					this.documents.remove(key);
					this.removeCount.incrementAndGet();
				}
			} else {
				this.documents.remove(key);
				this.documents.put(key, Document.ZOMBIE);
			}
		} finally {
			this.documentsLock.readLock().unlock();
		}
	}
	
	
	public DiskSegment save(File file) throws Exception {
		SegmentBuilder.build(this, file, DISK_BLOCK_SIZE);
		
		this.isSaved = true;
		
		return new DiskSegment(file);
		
	}

	public long size() {
		return this.documents.size();
	}
	
	/**
	 * Intercept puts to log the update
	 */
	public void update(Key key, Document doc, boolean propagate, boolean log) throws Exception {
		this.log(Operation.UPDATE,  key, doc, propagate, log);
		
		this.documentsLock.readLock().lock();
		
		this.documents.put(key, doc);
		this.getBloomFilter().set(this.getHash(key));
		
		GUID guid;
		
		if (key.isGUID() && (guid = key.getGUID()).compareTo(this.getHighGUID()) > 0) {
			this.setHighGUID(guid);
		}
		
		this.documentsLock.readLock().unlock();
	}

	private void timeoutLeases() {
		this.documentsLock.readLock().lock();
		
		Iterator<Map.Entry<Key, Lease>> iterator = this.leases.entrySet().iterator();
		
		while (iterator.hasNext()) {
			Map.Entry<Key, Lease> entry = iterator.next();
			Key key = entry.getKey();
			Lease lease = entry.getValue();
			
			if (System.currentTimeMillis() > lease.expiration || this.documents.get(key) == null) {
				iterator.remove();
			}			
		}
		
		this.documentsLock.readLock().unlock();
		
		if (this.removeCount.get() > 10000) {
			logger.info("*** compacting");
			this.documentsLock.writeLock().lock();
			this.documents = new ConcurrentSkipListMap<>(this.documents);
			this.documentsLock.writeLock().unlock();
		}
	}
	
	@Override
	public String toString() {
		return "MemorySegment [logFile=" + logFile.getName() + ", closed=" + closed + ", id=" + id +  "]";
	}

	public boolean isSaved() {
		return isSaved;
	}

	class SortedIterator implements Iterator<Map.Entry<Key, Blob>> {
		private final Map<Key, Document> map;
		private final List<Key> sortedKeys;
		private final Iterator<Key> iterator;
		
		SortedIterator(Map<Key, Document> map) {
			this.map = map;
			this.sortedKeys = new ArrayList<>(map.size());
			
			this.sortedKeys.addAll(map.keySet());
			
			Collections.sort(this.sortedKeys);
			
			this.iterator = this.sortedKeys.iterator();
		}
		
		@Override
		public boolean hasNext() {
			return this.iterator.hasNext();
		}

		@Override
		public Entry<Key, Blob> next() {
			try {
				Key key = this.iterator.next();
				Blob blob = BlobConverter.fromDocument(getMetadata(), this.map.get(key));
			
				return new SimpleEntry<>(key, blob);
			} catch (Exception exc) {
				throw new IllegalStateException(exc);
			}
		}
		
	}

}
