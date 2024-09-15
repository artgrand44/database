package org.art.db.storage;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock.ReadLock;
import java.util.concurrent.locks.ReentrantReadWriteLock.WriteLock;

import org.art.db.common.DiscoveryHandler;
import org.art.db.common.Document;
import org.art.db.common.DocumentSet;
import org.art.db.common.Key;
import org.art.db.common.Scheduler;
import org.art.db.common.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class Table implements Closeable {
	
	private static final Logger logger = LoggerFactory.getLogger(Table.class);
	
	private final LinkedList<Segment> segments = new LinkedList<>();
	private final Map<String, Index> indices;
	private volatile MemorySegment currentSegment;
	private volatile MemorySegment prevSegment;
	private final String table;
	private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
	private final ReadLock readLock = lock.readLock();
	private final WriteLock writeLock = lock.writeLock();
	private final DiscoveryHandler discoveryHandler;
	private final Environment environment;
	private final int maxSegmentSize;
	private final ScheduledFuture<?> switcher;
	private final ScheduledFuture<?> timer;
	private final boolean isQueue;
	private boolean closed;
	
	final static private DocumentCache cache = new DocumentCache();
	
	private static final LinkedBlockingQueue<MergeQueueEntry> mergeQueue =  new LinkedBlockingQueue<>();
	
	private static final long MAX_LOG_SIZE = 1024 * 1024 * 512;
	
	static {
		Scheduler.DEFAULT.submit(Table::mergerLoop);
	}
	
	public Table(String table, Map<String, Index> indices) throws Exception {
		Metadata meta = Metadata.getMetadata(table);
		
		this.table = table;
		this.indices = indices;
		this.discoveryHandler = DiscoveryHandler.getServerInstance();
		this.environment = Environment.getInstance();
		this.isQueue = meta.isQueue();
		this.maxSegmentSize = Integer.parseInt(this.environment.getProperty("org.art.db.maxSegmentSize", this.environment.getDefault("org.art.db.maxSegmentSize")));
		
		this.currentSegment = this.isQueue
				              ? new QueueSegment(this.table, this.discoveryHandler, this.environment)
				              : new MemorySegment(table, this.discoveryHandler, this.environment, false);
		
		this.segments.add(this.currentSegment);
			
		File[] files = Files.listDiskSegments(table);
		
		if (files != null) {
			List<File> list = Arrays.asList(files);
			Collections.sort(list, (a, b) -> - a.getName().compareTo(b.getName()));
			
			for (File file : list) {
				this.segments.add(new DiskSegment(file));
			}
		}
		
		this.switcher = Scheduler.DEFAULT.scheduleAtFixedRate(this::switchSegmentIfNeeded, 5, 10, TimeUnit.MILLISECONDS);
		this.timer = this.isQueue ? Scheduler.DEFAULT.scheduleAtFixedRate(this::timeoutSegments, 5, 5,  TimeUnit.MINUTES) : null;

	}
	
		@Override
	public void close() throws IOException {
		this.writeLock.lock();
		
		try {
			this.closed = true;
			
			if (this.isQueue) {
				this.timer.cancel(true);
			} else {
				while (this.prevSegment != null) {
					this.writeLock.unlock();
					Utils.sleepSafely(1000);
					this.writeLock.lock();
				}

				if (!this.currentSegment.isClosed() && this.currentSegment.size() != 0) {
					this.save(this.currentSegment);
				}
			}
			
			this.switcher.cancel(false);
		} catch (Exception exc) {
			throw new IOException(exc);
		} finally {
			this.writeLock.unlock();
		}
	}
	
	public void crash() throws Exception {
		if (this.currentSegment != null) {
			this.currentSegment.crash();
			this.currentSegment = this.isQueue
					              ? new QueueSegment(this.table, this.discoveryHandler, this.environment)
					              : new MemorySegment(this.table, this.discoveryHandler, this.environment, false);
			
		}
	}
	
	/**
	 * Flush the current segment. 
	 */
	public void flush() {
		MemorySegment segment = this.currentSegment;
		boolean saving = false;
		
		if (this.isQueue) {
			logger.info("flushing queue");
		}
		this.writeLock.lock();
		
		try {
			for (;;) {
				if (this.closed) {
					return;
				} else if (segment.isClosed() || segment.size() == 0) {
					return;
				} else if (this.prevSegment == null && !saving) {
					logger.info("flush saving");
					this.switchSegments(); 
					saving = true;
				} else if (segment.isSaved()) {
					logger.info("flush saved");
					return;				
				} else {
					this.writeLock.unlock();
					Utils.sleepSafely(1000);
					this.writeLock.lock();
				}
			}
		} catch (Exception exc) {
			Metrics.getInstance().logAlert("There was an error creating a database segment");
			logger.info("There was an error creating the new segment " + exc.getMessage());
		} finally {
			this.writeLock.unlock();
		}
	}
		
	
	public Document get(Key key) throws Exception {
		DocId id = new DocId(this, key);
		Document result = cache.getIfPresent(id); 

		if (result != null) {
			return result;
		}

		this.readLock.lock();

		try {
			for (Segment segment : this.segments) {
				if ((result = segment.get(key)) != null) {
					if (result.isZombie()) {
						break;
					} 
					
					cache.put(id, result);
					return result;	
				} else if (key.isGUID() && key.getGUID().compareTo(segment.getHighGUID()) > 0) {
					return null;
				} 
			}

			return null;
		} finally {
			this.readLock.unlock();
		}
	}
	
	public List<Segment> getSegments() {
		return Collections.unmodifiableList(this.segments);
	}
	
	boolean isEmpty() {
		return this.segments.isEmpty() || this.segments.size() == 1 && this.segments.get(0).size() == 0;
	}
	
	public DocumentSet lease(String owner, long timeout) {
		this.readLock.lock();
		
		DocumentSet result = new DocumentSet();
		
		try {
			Iterator<Segment> iterator = this.segments.reversed().iterator();

			while (iterator.hasNext()) {
				QueueSegment segment = (QueueSegment)iterator.next();
				result.addAll(segment.lease(owner, timeout));
			}
		} finally {
			this.readLock.unlock();
		}

		return result;
	}
	

	
	private void merge(File file) {

		try {
			if (this.isQueue) {
				logger.info("**** merging queue");
			}
			String[] tokens = file.getName().split("_");
			String size = tokens[1].split("\\.")[0];

			File[] files = Files.list(this.table, x -> x.getName().endsWith(size + ".seg"));

			if (files != null && files.length >= 10) {
				List<File> fileList = Arrays.asList(files);
				File merged = Files.getDiskSegment(this.table, Integer.parseInt(size) * 10);

				SegmentBuilder.merge(merged, Segment.DISK_BLOCK_SIZE, files);

				this.writeLock.lock();

				for (int idx = 0; idx < this.segments.size();) {
					Segment segment = this.segments.get(idx);

					if (segment instanceof MemorySegment) {
						idx++;
					} else if (((DiskSegment)segment).getFile().equals(file)) {
						this.segments.set(idx, new DiskSegment(merged));
						idx++;
					} else if (fileList.contains(((DiskSegment)segment).getFile())) {
						this.segments.remove(idx);
					} else {
						idx++;
					}
				}

				for (File afile : fileList) {
					afile.delete();
				}

				this.writeLock.unlock();
				mergeQueue.add(new MergeQueueEntry(this, file));
			}	
		} catch (Exception exc) {
			Metrics.getInstance().logAlert("There was an error merging database segments");
			logger.info("Error merging files " + exc.getMessage());
		}
	}
	
	private static void mergerLoop() {
		for (;;) {
			try {
				MergeQueueEntry entry = mergeQueue.take();
				
				entry.table.merge(entry.file);
			} catch (InterruptedException exc) {
				break;
			} catch (Exception exc) {
				logger.info("Merge exception " + exc.getMessage());
			}
				
		}
	}
		
	public void mergeAll() {
		for (Segment segment : this.segments) {
			if (segment instanceof DiskSegment disk) {
				mergeQueue.add(new MergeQueueEntry(this, disk.getFile()));
			}
		}
	}
	public void insert(Key key, Document value, boolean propagate, boolean log) throws Exception {
		this.readLock.lock();
		
		try {
			this.currentSegment.insert(key, value, propagate, log);	
			
			cache.put(new DocId(this, key), value);
		} finally {
			this.readLock.unlock();
		}

	}
	
	
	public void remove(Key key, boolean propagate, boolean log) throws Exception {
		this.readLock.lock();
		
		try {
			cache.invalidate(new DocId(this, key));
			
			if (this.isQueue) {
				for (Segment segment : this.segments) {
					((MemorySegment)segment).remove(key, segment == this.currentSegment && propagate, log);
				}
			} else {
				this.currentSegment.remove(key, propagate, log);
			}			
		} finally {
			this.readLock.unlock();
		}
		
	}
	
	private void save(MemorySegment segment) {
		try {
			File file  = Files.getDiskSegment(this.table, 1);

			DiskSegment saved = segment.save(file);
			
			this.writeLock.lock();
			
			try {
				boolean replaced = false;

				for (int idx = 0; idx < this.segments.size(); idx++) {
					if (this.segments.get(idx).equals(segment)) {
						this.segments.set(idx, saved);
						replaced = true;
						break;
					}
				}

				if (!replaced) {					
					logger.info("***** failed to replace segment " + segment + "\n" + this.segments);
				}

				segment.close();
			} finally {
				this.writeLock.unlock();
				mergeQueue.add(new MergeQueueEntry(this, file));
			}

		} catch (Exception exc) {
			Metrics.getInstance().logAlert("There was an error merging database segments");
			exc.printStackTrace();
			logger.info("Error merging files " + exc.getMessage());
		}

	}
	
	private void switchSegments() {
		try {
			for (Index index : this.indices.values()) {
				index.flush();
			}

			this.prevSegment = this.currentSegment;
			
			if (this.isQueue) {
				this.currentSegment = new QueueSegment(this.table, this.discoveryHandler, this.environment);
			} else {
				Scheduler.DEFAULT.submit(() -> { this.save(this.prevSegment); this.prevSegment = null;});
				this.currentSegment = new MemorySegment(this.table, this.discoveryHandler, this.environment, true);
			}
			
			this.segments.addFirst(this.currentSegment);
		} catch (Exception exc) {
			Metrics.getInstance().logAlert("There was an error switching database segments");
			logger.info("Exception switching segments " + exc.getMessage());
		}
	}
	
	private void switchSegmentIfNeeded() {
		this.writeLock.lock();

		try {
			if (this.currentSegment.size() > this.maxSegmentSize && this.prevSegment == null && !this.closed) {
				switchSegments();
			} else if (this.isQueue && this.currentSegment.getLogSize() > MAX_LOG_SIZE) {
				switchSegments();
			} 
		} finally {
			this.writeLock.unlock();
		}
	}
	
	public void update(Key key, Document value, boolean propagate, boolean log) throws Exception {
		this.readLock.lock();
		
		try {
			this.currentSegment.update(key, value, propagate, log);	
			
			cache.put(new DocId(this, key), value);
		} finally {
			this.readLock.unlock();
		}
		
	}
	
	private void timeoutSegments() {
		
		this.writeLock.lock();
		
		try {
			Iterator<Segment> iterator = this.segments.iterator();
			
			while (iterator.hasNext()) {
				MemorySegment segment = (MemorySegment)iterator.next();
				
				if (segment != this.currentSegment && segment.size() == 0) {
					segment.deleteLog();
					Utils.closeSafely(segment);
					iterator.remove();
				}
			}
		} finally {
			this.writeLock.unlock();
		}
	}
	
	static record DocId(Table table, Key key) {
		
	}
	
	static class DocumentCache extends ConfigurableCache<DocId, Document> {
		DocumentCache() {
			super("org.art.db.documentCache");
		}
		
		@Override
		public int weigh(DocId key, Document doc) {
			return doc.getSize();
		}
	}

	@Override
	public String toString() {
		return "Table [segments=" + segments + ", currentSegment=" + currentSegment + ", table=" + table + "]";
	}
	
	static record MergeQueueEntry(Table table, File file) {
		
	}
	
	
}
