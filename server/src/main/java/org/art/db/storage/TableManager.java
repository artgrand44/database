package org.art.db.storage;

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.art.db.common.Blob;
import org.art.db.common.DBRuntimeException;
import org.art.db.common.DiscoveryHandler;
import org.art.db.common.Document;
import org.art.db.common.Key;
import org.art.db.common.LogEntry;
import org.art.db.common.RestClient;
import org.art.db.common.RestException;
import org.art.db.common.Scheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

public class TableManager implements Closeable{
	private static final Logger logger = LoggerFactory.getLogger(TableManager.class);
	
	private final String tableName;
	private final Table table;
	private final File metadataFile;
	private final List<IndexInfo> indexInfo;
	private final Map<String, Index> indices = new HashMap<>();
	private final LockManager<Key> lockManager = new LockManager<>();
	private final boolean checkConstraints;
	private final RestClient restClient = new RestClient();
	private Metadata metadata;
	
	private final Cache<Key, Long> timestamps = Caffeine.newBuilder().maximumSize(1000).expireAfterWrite(Duration.ofMinutes(10)).build();
	
	public TableManager(String tableName) throws Exception {
		this.tableName = tableName;
		this.metadataFile = Files.getMetadata(tableName);
		this.metadata = metadataFile.exists() ? new ObjectMapper().readValue(metadataFile, Metadata.class) : new Metadata(tableName);
		this.indexInfo = metadata.indices();
		
		for (IndexInfo info : this.indexInfo) {
			String name = info.name();
			File indexFile = Files.getIndex(tableName, name);
			
			this.indices.put(name, new Index(indexFile, info.unique()));
		}
		
		File[] logs =  Files.listTableLogs(tableName);
		this.table = new Table(tableName, this.indices);
				
		if (logs != null) {
			List<File> list = Arrays.asList(logs);
			
			Collections.sort(list, (a, b) -> - a.getName().compareTo(b.getName()));

			for (File file : list) {
				if (file.length() != 0) {
					this.applySnapshot(new FileInputStream(file), false);
				} 
			}
		}
		
		this.checkConstraints = true;
	}
	
	public void applySnapshot(InputStream stream, boolean checkTimestamps) {
		
		try (LogInputStream log = new LogInputStream(stream)) {
			LogEntry entry;
			
			while ((entry = (LogEntry)log.read()) != null) {
				if (checkTimestamps) {
					Long localTimestamp = this.timestamps.getIfPresent(entry.getKey());
					long remoteTimestamp = entry.getTimestamp();

					if (localTimestamp != null && localTimestamp >= remoteTimestamp) {
						continue;
					}
				}
				
				try {
					switch (entry.getOp()) {
					case INSERT:
						this.insert(entry.getDocument(), false);
						break;
					case REMOVE:
						this.remove(this.table.get(entry.getKey()), false);
						break;
					case UPDATE:
						this.update(this.table.get(entry.getKey()), entry.getDocument(), false);
						break;
					default:
						logger.info("bad op " + entry);
						break;

					}
				} catch (Exception exc) {
					logger.info("There was an exception recovering a log entry " + exc.getMessage() + " " + entry + " check " + checkTimestamps);
				}
			}
		} catch (Exception exc) {
			logger.info("There was an exception recovering the log " + exc.getMessage());
		}
		
	}
	
	private Key buildKey(Document doc, List<String> names) {
		List<Comparable<?>> keys = new ArrayList<>();
		
		for (String name : names) {
			Comparable<?>  value = (Comparable<?>)doc.get(name);
			
			if (value == null) {
				break;
			} else {
				keys.add(value);
			}
		}
		
		return new Key(keys);
	}
	
	private void checkForDuplicateKey(IndexInfo info, Document doc) throws Exception {		
		Index index = this.indices.get(info.name());
		
		Key indexKey = this.buildKey(doc, info.fields());
		
		this.lockManager.writeLock(new Key(info.name(), indexKey));
		
		if (index.get(indexKey) != null) {
			throw new DBRuntimeException("Duplicate value for " + info.name());
		}
	}
	
	public void flush() {
		this.table.flush();
	}
	
	public Document get(Key key) throws Exception {
		return this.table.get(key);
	}
	
	public Set<Key> getAllKeys(String indexName, Comparison comp, Key key) throws Exception {
		return this.indices.get(indexName).getAllKeys(comp, key);
	}
	
	public Set<Key> getAll(String indexName, Comparison comp, Key key) throws Exception {
		return this.indices.get(indexName).getAll(comp, key);
	}
	
	public Set<Key> getAllBetween(String indexName, Key first, Key last) throws Exception {
		return this.indices.get(indexName).getAllBetween(first, last);
	}
	
	public Document getDocument(Key key) throws Exception {
		Document doc = this.table.get(key);
		
		if (doc != null) {
			doc.setKey(key);
		}
		
		return doc;
	}
	
	public int getSelectivity(String indexName) {
		return this.indices.get(indexName).getSelectivity();
	}
	
	public Metadata getMetadata() {
		return this.metadata;
	}
	
	public void insert(Document doc, boolean propagate) throws Exception {
		
		try {
			Key primaryKey = this.buildKey(doc, this.metadata.primaryKeyFields());

			if (this.checkConstraints) {
				this.lockManager.writeLock(new Key("", primaryKey));
				
				Document old = this.table.get(primaryKey);
				
				if (old != null) {
					throw new DBRuntimeException("Duplicate primary key " + primaryKey);
				}

				for (int idx = 0; idx < this.indexInfo.size(); idx++) {
					IndexInfo info = this.indexInfo.get(idx);

					if (info.unique()) {
						this.checkForDuplicateKey(info, doc);
					}
				}
			}
			
			this.timestamps.put(primaryKey, System.currentTimeMillis());
			this.table.insert(primaryKey, doc, propagate);
			this.updateIndices(null, doc, primaryKey);
		} finally {
			this.lockManager.unlockAll();
		}
		
	}
	
	/**
	 * Recover the table after a restore.  The recovery process
	 * copies the missing segments from another instance and then
	 * updates the indices to reflect the missing segments.
	 * 
	 * @param peer The peer to copy the segments from
	 * @param thisURI The URI of this instance
	 * @throws Exception
	 */
	public void recover(DiscoveryHandler handler) throws Exception {
		
		
		File[] files = Files.listDiskSegments(this.tableName);
		long timestamp;
		int mergeLevel;
		DiskSegment segment;
		
		// Find the timestamp and merge level for the newest segment on this
		// server
		
		if (files == null || files.length == 0) {			
			logger.info("No segments");
			timestamp = 0;
			mergeLevel = 1;
		} else {
			List<File> list = Arrays.asList(files);
			Collections.sort(list, (a, b) -> - a.getName().compareTo(b.getName()));
			
			segment = new DiskSegment(list.get(0));
			timestamp = segment.getCreateTime();
			mergeLevel = segment.getMergeLevel();
		}
		
		
		// Pick a random instance in this shard and try to copy its segments.
		// We try up to 10 instances before giving up.  If an instance goes
		// down in the middle of a recovery, we delete all the newly recovered 
		// segments and try again.
		
		List<File> filesCreated = new ArrayList<>();
		File downloadFile = Files.getDiskSegment(this.tableName, 0);
		
		for (int idx = 0; idx < 10; idx++) {
			URI peer = handler.getRandomPeer();

			if (peer == null) {
				logger.info("no peers");
				return;
			}
			
			try {
				
				// Tell the peer to flush its current segment and to begin sending broadcasts to this
				// instance.  The peer will continue sending the broadcasts until this instance is up
				// and running.

				this.restClient.get(restClient.buildURI(peer, "/db/recovering?uri=%s&table=%s", handler.getLocalInstance(), this.tableName));

				// Process the missing segments from oldest to newest.  Continue until we get an empty file.
				// The empty file acts as a "no more segments" indicator.
			

				for (;;) {
					this.restClient.getFile(restClient.buildURI(peer, "/db/getnextfile?table=%s&timestamp=%s", this.tableName, timestamp), downloadFile);

					if (downloadFile.length() == 0) {
						logger.info("Empty file");
						downloadFile.delete();
						return;
					}

					// Load the segment and process the zombies

					segment = new DiskSegment(downloadFile);

					for (Key key : segment.getZombies()) {
						Document doc = this.get(key);

						if (doc != null) {
							logger.info("Remove " + key);
							this.remove(doc, false);
						}
					}

					// Iterate over the segments and update the index entries for each
					// document.  This process is i/o intensive, so we process several
					// documents in parallel.

					Iterator<Map.Entry<Key, Blob>> iterator = segment.iterator();
					AtomicInteger inProgress = new AtomicInteger();

					while (iterator.hasNext()) {
						Map.Entry<Key, Blob> entry = iterator.next();
						Key key = entry.getKey();
						Blob blob = entry.getValue();

						inProgress.incrementAndGet();
						Scheduler.DEFAULT.submit(() -> this.recoverIndexEntries(key, blob, inProgress));
					}

					while (inProgress.get() != 0) {
						Thread.sleep(10);
					}

					mergeLevel = segment.getMergeLevel();
					timestamp = segment.getCreateTime();

					segment.close();

					// Rename the segment to match the name on the peer

					File renamed = Files.getDiskSegment(this.tableName, timestamp, mergeLevel);

					downloadFile.renameTo(renamed);
					filesCreated.add(renamed);

					logger.info("processed  " + renamed.getAbsolutePath());
				}				
			} catch (RestException exc) {
				for (File file : filesCreated) {
					file.delete();
				}
				
				if (exc.getStatus() == RestException.NO_CONNECTION || exc.getStatus() == RestException.SERVER_DOWN) {
					logger.info("doCall got fail on " + peer);
					handler.removeInstance(peer);
				} else {
					throw exc;
				}
			} finally {
				downloadFile.delete();
			}

		}		
		
	}
	
	/**
	 * Update the index entries for a recovered document
	 * @param key
	 * @param blob
	 * @param inProgress
	 */
	void recoverIndexEntries(Key key, Blob blob, AtomicInteger inProgress) {
		try {
			Document oldDoc = this.get(key);
			Document newDoc = BlobConverter.toDocument(this.metadata, blob);
		
			this.updateIndices(oldDoc, newDoc, key);
		} catch (Exception exc) {
			logger.info("Error recovering index entries " + key + " " + exc.getMessage());
		} finally {
			inProgress.decrementAndGet();
		}
	}
	
	public void remove(Document doc, boolean propagate) throws Exception {
		Key primaryKey = this.buildKey(doc, this.metadata.primaryKeyFields());
		
		this.timestamps.put(primaryKey, System.currentTimeMillis());
		this.table.remove(primaryKey, propagate);
		this.updateIndices(doc, null, primaryKey);
	}
	
	public void update(Document oldDoc, Document newDoc, boolean propagate) throws Exception {
		try {
			Key primaryKey = this.buildKey(newDoc, this.metadata.primaryKeyFields());

			if (this.checkConstraints) {
				for (int idx = 0; idx < this.indexInfo.size(); idx++) {
					IndexInfo info = this.indexInfo.get(idx);
					Key oldKey = this.buildKey(oldDoc, info.fields());
					Key newKey = this.buildKey(newDoc, info.fields());
					
					if (info.unique() && !oldKey.equals(newKey)) {
						this.checkForDuplicateKey(info, newDoc);
					}
				}	
			}
			
			this.timestamps.put(primaryKey, System.currentTimeMillis());
			this.table.update(primaryKey, newDoc, propagate);			
			this.updateIndices(oldDoc, newDoc, primaryKey);
		} finally {
			this.lockManager.unlockAll();
		}
	}
	
	public void updateIndices(Document oldDoc, Document newDoc, Key primaryKey) throws Exception {

		for (int idx = 0; idx < this.indexInfo.size(); idx++) {
			IndexInfo info = this.indexInfo.get(idx);
			Index index = this.indices.get(info.name());
			Key oldKey = oldDoc == null ? null : this.buildKey(oldDoc, info.fields());
			Key newKey = newDoc == null ? null : this.buildKey(newDoc, info.fields());

			if (!Objects.equals(oldKey, newKey)) {
				if (oldKey != null) {
					index.remove(oldKey, primaryKey);
				}
				
				if (newKey != null) {
					index.put(newKey, primaryKey);
				}
			}
		}

	}
	
	public void setMetadata(Metadata info) throws Exception {
		this.metadata = info;
		new ObjectMapper().writeValue(this.metadataFile, info);
	}
	
	@Override
	public void close() throws IOException {
		this.table.close();
		
		for (Map.Entry<String, Index> entry : this.indices.entrySet()) {
			entry.getValue().close();
		}
		
	}
	
	public void crash() throws Exception {
		this.table.crash();
	}
}
