package org.art.db.storage;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock.ReadLock;
import java.util.concurrent.locks.ReentrantReadWriteLock.WriteLock;

import org.art.db.common.Configuration;
import org.art.db.common.DiscoveryHandler;
import org.art.db.common.Document;
import org.art.db.common.Key;
import org.art.db.common.Scheduler;
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
	private final Configuration config;
	private final int maxSegmentSize;
	private final ScheduledFuture<?> switcher;
	private boolean closed;
	
	final static private DocumentCache cache = new DocumentCache();
	
	private static final LinkedBlockingQueue<MergeQueueEntry> mergeQueue =  new LinkedBlockingQueue<>();
	
	static {
		for (int idx = 0; idx < 3; idx++) {
			Scheduler.DEFAULT.submit(Table::mergerLoop);
		}
	}
	
	public Table(String table, Map<String, Index> indices) throws Exception {
		this.table = table;
		this.indices = indices;
		this.discoveryHandler = DiscoveryHandler.getServerInstance();
		this.config = Configuration.getInstance();
		this.maxSegmentSize = Integer.parseInt(this.config.getProperty("org.art.db.maxSegmentSize", this.config.getDefault("org.art.db.maxSegmentSize")));
		this.currentSegment = new MemorySegment(table, this.discoveryHandler, this.config);
		
		this.segments.add(this.currentSegment);
			
		File[] files = Files.listDiskSegments(table);
		
		if (files != null) {
			List<File> list = Arrays.asList(files);
			Collections.sort(list, (a, b) -> - a.getName().compareTo(b.getName()));
			
			for (File file : list) {
				logger.info("found file " + file.getAbsolutePath());
				this.segments.add(new DiskSegment(file));
			}
		}
		
		this.switcher = Scheduler.DEFAULT.scheduleAtFixedRate(this::switchSegmentIfNeeded, 5, 5, TimeUnit.SECONDS);

	}
	
	@Override
	public void close() throws IOException {
		this.writeLock.lock();
		
		try {
			this.closed = true;
			
			while (this.prevSegment != null) {
				this.writeLock.unlock();
				Thread.sleep(1000);
				this.writeLock.lock();
			}
			
			this.save(this.currentSegment);
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
			this.currentSegment = new MemorySegment(this.table, this.discoveryHandler, this.config);
		}
	}
	
	public Document get(Key key) throws Exception {
		DocId id = new DocId(this, key);
		Document result = cache.getIfPresent(id); 

		if (result != null) {
			//return result;
		}

		this.readLock.lock();

		try {
			List<String> lowKeys = new ArrayList<>();

			for (Segment segment : this.segments) {
				lowKeys.add(segment.getHighGUID().toString());
			}

			for (Segment segment : this.segments) {
				if (segment.getZombies().contains(key)) {
					return null;
				} else if ((result = segment.get(key)) != null) {
					cache.put(id, result);
					
					return result;
				} /*else if (key.isGUID() && key.getGUID().compareTo(segment.getHighGUID()) > 0) {
					return null;
				} */
			}

			return null;
		} finally {
			this.readLock.unlock();
		}
	}
	
	private void merge(File file) {

		try {
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
		

	public void insert(Key key, Document value, boolean propagate) throws Exception {
		this.readLock.lock();
		
		try {
			this.currentSegment.insert(key, value, propagate);	
			
			cache.put(new DocId(this, key), value);
		} finally {
			this.readLock.unlock();
		}

	}
	
	@SuppressWarnings("unused")
	private void printStats() {
		for (Map.Entry<String, Index> entry : this.indices.entrySet()) {
			logger.info(String.format("%20s   %s", entry.getKey(), entry.getValue().getStats()));
		}
	}
	
	public void remove(Key key, boolean propagate) throws Exception {
		this.readLock.lock();
		
		try {
			this.currentSegment.remove(key, propagate);
			cache.invalidate(new DocId(this, key));
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
					mergeQueue.add(new MergeQueueEntry(this, file));
				} finally {
					this.writeLock.unlock();
				}

			} catch (Exception exc) {
				logger.info("Error merging files " + exc.getMessage());
			}
		

	}
	
	private void switchSegmentIfNeeded() {
		this.writeLock.lock();

		try {
			if (this.currentSegment.size() > this.maxSegmentSize && this.prevSegment == null && !this.closed) {
				for (Index index : this.indices.values()) {
					index.flush();
				}

				this.prevSegment = this.currentSegment;
				Scheduler.DEFAULT.submit(() -> { this.save(this.prevSegment); this.prevSegment = null; });
								
				this.currentSegment = new MemorySegment(this.table, this.discoveryHandler, this.config);
				this.segments.addFirst(this.currentSegment);
			}
		} catch (Exception exc) {
			logger.info("There was an error creating the new segment " + exc.getMessage());
		} finally {
			this.writeLock.unlock();
		}
	}
	
	public void update(Key key, Document value, boolean propagate) throws Exception {
		this.readLock.lock();
		
		try {
			this.currentSegment.update(key, value, propagate);	
			
			cache.put(new DocId(this, key), value);
		} finally {
			this.readLock.unlock();
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
