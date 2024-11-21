package org.art.db.storage;

import java.io.File;
import java.io.IOException;
import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

import org.art.db.common.Blob;
import org.art.db.common.DiscoveryHandler;
import org.art.db.common.Document;
import org.art.db.common.GUID;
import org.art.db.common.Key;
import org.art.db.common.LogEntry;
import org.art.db.common.LogEntry.Operation;
import org.art.db.common.Scheduler;
import org.art.db.common.TypedOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An in-memory DataTable that is restored to its previous
 * state after a crash
 */

public class MemorySegment extends Segment {
	
	@SuppressWarnings("unused")
	private static final Logger logger = LoggerFactory.getLogger(MemorySegment.class);
	
	
	/**
	 * The log file
	 */
	protected final File logFile;
	
	/**
	 * The log
	 */
	public final LogOutputStream log;
	
	/**
	 * The table name
	 */
	private final String table;
	
	/**
	 * Has the segment been closed
	 */
	private volatile boolean closed = false;
	
	/**
	 * The documents in this segment
	 */
	private volatile ConcurrentSkipListMap<Key, Document> documents = new ConcurrentSkipListMap<>();
	
	/**
	 * The number of documents in the segment
	 */
	private AtomicLong documentCount = new AtomicLong();
	
	private static AtomicLong nextId = new AtomicLong();
	
	private final String id;

	/**
	 * The batches that need to be broadcast
	 */
	private final Deque<List<LogEntry>> broadcastQueue = new ConcurrentLinkedDeque<List<LogEntry>>();
	
	
	/**
	 * The current batch being build up
	 */
	protected ConcurrentLinkedDeque<LogEntry> currentBroadcastBatch = new ConcurrentLinkedDeque<>();
	
	/**
	 * Has the segment been saved
	 */
	private boolean isSaved = false;
	
	
	/**
	 * The timer for async broadcasts
	 */
	private final ScheduledFuture<?> broadcastHandler;
	

	/**
	 * Lock for queuing up broadcasts
	 */
	private final ReentrantLock broadcastLock = new ReentrantLock();
	
	
	/**
	 * Discovery handler for sending snapshots
	 */
	protected final DiscoveryHandler discoveryHandler;
	
	
	private static ScheduledExecutorService SCHEDULER = Scheduler.createScheduler("MemSeg", 5);
	

	public MemorySegment(String table, DiscoveryHandler discoveryHandler, Environment config) throws Exception {
		super();
		
		Interval interval;
		
		try {
			interval = new Interval(config.getProperty("org.art.db.logDelay", config.getDefault("org.art.db.logDelay")));
		} catch (Exception exc) {

			interval = new Interval("5s");
		}
		
		long delay = interval.toMillis();
		
		this.logFile = DBFiles.getTableLog(table);
		this.log = new LogOutputStream(this.logFile);
		this.table = table;
		this.discoveryHandler = discoveryHandler;
		this.broadcastHandler = SCHEDULER.scheduleAtFixedRate(this::broadcastTimer, 1,  delay,  TimeUnit.MILLISECONDS);
		this.id = "Mem" + nextId.incrementAndGet();		
		
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
		
		List<LogEntry> batch;

		while ((batch = this.broadcastQueue.pollFirst()) != null) {
			try {
				try (TypedOutputStream stream = new TypedOutputStream()) {
					for (LogEntry entry : batch) {
						if (entry.isPropagate()) {
							stream.writeObject(entry);
						}
					}
					
					Broadcaster.getInstance().broadcast("/db/propagate?table=" + this.table, stream.toByteArray());			
				}
			} catch (Exception exc) {
				if (batch != null) {
					this.broadcastQueue.addFirst(batch);
				}
			}
		}
				
	}
	
	public void close() throws IOException {
		if (this.closed) {
			new Exception().printStackTrace();
		}
		
		try {
			if (this.broadcastHandler != null) {
				this.broadcastHandler.cancel(false);
			}
			
			this.broadcastTimer();
			this.log.close();
			this.documents = null;
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
	
	protected void log(Operation op, Key key, Document value, boolean propagate, boolean log) throws Exception {

		LogEntry entry = new LogEntry(op, key, value, System.currentTimeMillis(), propagate);

		if (log) {
			this.log.write(entry);
		}

		if (propagate) {
			this.currentBroadcastBatch.add(entry);
		}
	
	}
	
	@Override
	public void deleteLog() {
		this.logFile.delete();
	}
	
	@Override
	public Document get(Key key) throws Exception {
		return this.documents.get(key);
	}

	@Override
	public long getDocumentCount() {
		return this.documentCount.get();
	}
	
	public long getLogSize() {
		return this.log.getLength();
	}
	
	/**
	 * Intercept puts to log the update
	 */
	public void insert(Key key, Document doc, boolean propagate, boolean log) throws Exception {
		this.log(Operation.INSERT,  key, doc, propagate, log);
		
		this.documents.put(key, doc);
		
		this.documentCount.incrementAndGet();
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
	
	
	/**
	 * Intercept a remove to log the updates
	 */
	public void remove(Key key, boolean propagate, boolean log) throws Exception {
		this.log(Operation.REMOVE, key, null, propagate, log);
		
		if (this.documents.remove(key) != null) {
			this.documentCount.decrementAndGet();
		}
		
		this.documents.put(key, Document.ZOMBIE);	
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
		
		this.documents.put(key, doc);
		this.getBloomFilter().set(this.getHash(key));
		
		GUID guid;
		
		if (key.isGUID() && (guid = key.getGUID()).compareTo(this.getHighGUID()) > 0) {
			this.setHighGUID(guid);
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
