package org.art.db.storage;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

import org.art.db.common.Blob;
import org.art.db.common.Configuration;
import org.art.db.common.DiscoveryHandler;
import org.art.db.common.Document;
import org.art.db.common.GUID;
import org.art.db.common.Key;
import org.art.db.common.RestClient;
import org.art.db.common.Scheduler;
import org.art.db.common.TypedOutputStream;
import org.art.db.storage.SegmentLog.LogEntry;
import org.art.db.storage.SegmentLog.Operation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An in-memory DataTable that is restored to its previous
 * state after a crash
 */
public class MemorySegment extends Segment {
	private static final Logger logger = LoggerFactory.getLogger(MemorySegment.class);
	
	private static AtomicLong nextId = new AtomicLong();
	
	private static ScheduledExecutorService SCHEDULER = Scheduler.createScheduler("MemSeg", 5);
	
	/**
	 * The log file
	 */
	private final File logFile;
	
	/**
	 * The log
	 */
	private final SegmentLog log;
	
	/**
	 * The table name
	 */
	private final String table;
	
	/**
	 * Has the segment been closed
	 */
	private volatile boolean closed = false;
	
	/**
	 * The rest client for sending snapshots
	 */
	private final RestClient restClient = new RestClient();
		
	private final String id;
	
	/**
	 * The batch for async broadcasts
	 */
	private List<LogEntry> broadcastBatch = new ArrayList<>();
	
	/**
	 * The documents in this segment
	 */
	private final ConcurrentHashMap<Key, Document> documents = new ConcurrentHashMap<Key, Document>(10000);
	
	/**
	 * The timer for async broadcasts
	 */
	private final ScheduledFuture<?> timer;
	
	/**
	 * Lock for logging
	 */
	private final ReentrantLock lock = new ReentrantLock();
	
	/**
	 * Discovery handler for sending snapshots
	 */
	private final DiscoveryHandler discoveryHandler;
	
	private final long logDelay;
	

	public MemorySegment(String table, DiscoveryHandler discoveryHandler, Configuration config) throws Exception {
		super(table);
		
		Interval interval;
		
		try {
			interval = new Interval(config.getProperty("org.art.db.logDelay", config.getDefault("org.art.db.logDelay")));
		} catch (Exception exc) {
			interval = new Interval("0s");
		}
		
		this.logFile = Files.getTableLog(table);
		this.log = new SegmentLog(this.logFile, true);
		this.table = table;
		this.discoveryHandler = discoveryHandler;
		this.logDelay = interval.toMillis();
		this.timer = logDelay == 0 ? null : SCHEDULER.scheduleAtFixedRate(this::timer, 1,  1,  TimeUnit.SECONDS);
		this.id = "Mem" + nextId.incrementAndGet();		
		
	}
	
	private void broadcastSnapshot(List<LogEntry> batch) throws Exception {

		this.lock.lock();

		try {
			Collection<URI> peers;

			if (this.discoveryHandler == null || (peers = this.discoveryHandler.getLocalPeers()).isEmpty() || batch.size() == 0) {
				return;
			}

			try (TypedOutputStream stream = new TypedOutputStream()) {
				stream.writeUTF(this.table);

				for (LogEntry entry : batch) {
					if (entry.propagate()) {
						this.log.write(entry, stream);
					}
				}

				logger.info("bradcast from " + this.discoveryHandler.getLocalPort() + " to " + peers);
				this.restClient.broadcast(peers, "/db/propagate", stream.toByteArray());

			}
		} finally {
			this.lock.unlock();
		}
	}
		
	public void close() throws IOException {
		this.lock.lock();
		
		if (this.closed) {
			new Exception().printStackTrace();
		}
		
		try {
			if (this.timer != null) {
				this.timer.cancel(true);
			}
			
			this.log.close();
			this.logFile.delete();
			super.close();
		} finally {
			this.closed = true;
			this.lock.unlock();
		}
	}
	
	public void crash() throws IOException {
		logger.info("in crash " + this.logFile.getAbsolutePath() + " " + this.logFile.length());
		super.close();
		this.log.close();
	}
	

	public Document get(Key key) throws Exception {
		if (!this.documents.containsKey(key)) {
			logger.info("no key in mem " + this.documents.get(key));
		}
		return this.documents.get(key);
	}
	
	/**
	 * Insert a document
	 */
	public void insert(Key key, Document doc, boolean propagate) throws Exception {
		this.log(Operation.INSERT,  key, doc, propagate);
			
		this.documents.put(key, doc);
		this.getBloomFilter().set(this.getHash(key));
		this.getZombies().remove(key);
				
		GUID guid;
		
		if (key.isGUID() && (guid = key.getGUID()).compareTo(this.getHighGUID()) > 0) {
			this.setHighGUID(guid);
		}
	}

	public boolean isClosed() {
		return this.closed;
	}
	
	@Override
	public Iterator<Entry<Key, Blob>> iterator() {
		return new SortedIterator(this.documents);
	}

	/**
	 * Log an update
	 * @param key
	 * @param value
	 * @throws Exception
	 */
	
	private void log(Operation op, Key key, Document value, boolean propagate) throws Exception {
		
		this.lock.lock();
		
		try {
			LogEntry entry = new LogEntry(op, key, value, System.currentTimeMillis(), propagate);
			
			if (this.closed) {
				logger.info("log after close " + this);
			}
			this.log.write(entry);
			this.log.flush();

			if (propagate) {
				if (this.logDelay == 0) {
					this.broadcastSnapshot(Collections.singletonList(entry));
				} else {
					this.broadcastBatch.add(entry);
				}	
			}
		} finally {
			this.lock.unlock();
		}
	
	}
	
	/**
	 * Intercept a remove to log the updates
	 */
	public void remove(Key key, boolean propagate) throws Exception {
		this.log(Operation.REMOVE, key, null, propagate);
		
		this.documents.remove(key);
		this.getZombies().add(key);;
	}
	
	public DiskSegment save(File file) throws Exception {
		SegmentBuilder.build(this, file, DISK_BLOCK_SIZE);
		
		return new DiskSegment(file);
	}
	
	@Override
	public long size() {
		return this.documents.size();
	}

	/**
	 * Fire an async broadcast
	 */
	private void timer() {
		if (this.broadcastBatch.size() != 0) {
			try {
				this.broadcastSnapshot(this.broadcastBatch);
				this.broadcastBatch.clear();
			} catch (Exception exc) {
				exc.printStackTrace();
				logger.info("Async broadcast failed " + exc.getMessage());
			}
		}
	}

	@Override
	public String toString() {
		return "MemorySegment [logFile=" + logFile.getName() + ", closed=" + closed + ", id=" + id +  "]";
	}
	
	/**
	 * Intercept puts to log the update
	 */
	public void update(Key key, Document doc, boolean propagate) throws Exception {
		this.log(Operation.UPDATE,  key, doc, propagate);
		
		this.documents.put(key, doc);
		this.getBloomFilter().set(this.getHash(key));
		this.getZombies().remove(key);
		
		GUID guid;
		
		if (key.isGUID() && (guid = key.getGUID()).compareTo(this.getHighGUID()) > 0) {
			this.setHighGUID(guid);;
		}		
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
