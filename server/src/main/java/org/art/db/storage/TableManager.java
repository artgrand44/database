package org.art.db.storage;

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.art.db.common.Comparison;
import org.art.db.common.DBNull;
import org.art.db.common.DBRuntimeException;
import org.art.db.common.Document;
import org.art.db.common.DocumentSet;
import org.art.db.common.Key;
import org.art.db.common.LogEntry;
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
	private final LockManager<Key> primaryKeyLockManager = new LockManager<>();
	private final LockManager<Key> alternateKeyLockManager = new LockManager<>();
	private boolean checkConstraints;
	
	private final FileLock lock;
	private Metadata metadata;
	
	private final Cache<Key, Long> timestamps = Caffeine.newBuilder().maximumSize(1000).build();
	
	public TableManager(String tableName) throws Exception {
		this.tableName = tableName;
		this.metadataFile = Files.getMetadata(tableName);
		this.metadata = metadataFile.exists() ? new ObjectMapper().readValue(metadataFile, Metadata.class) : new Metadata(tableName);
		this.indexInfo = metadata.indices();
		
		RandomAccessFile lockFile = new RandomAccessFile(Files.getLockFile(this.tableName), "rw");
		
		this.lock = lockFile.getChannel().tryLock(0, Long.MAX_VALUE, false);
		
		if (this.lock == null) {
			throw new DBRuntimeException(tableName + " is locked by another serve");
		}
		
		for (IndexInfo info : this.indexInfo) {
			String name = info.name();
			File indexFile = Files.getIndex(tableName, name);
			
			this.indices.put(name, new Index(indexFile, info.unique()));
		}
		
		File[] logs =  Files.listTableLogs(tableName);
		
		this.table = new Table(tableName, this.indices);
				
		// If there are any logs for this table then apply them.  If this is a 
		// queue then apply the oldest logs immediately and apply the rest in the
		// background.  Logs for queues can be very large.  By applying the
		// newer logs in the background, we allow the server to come up more quickly.
		
		int maxImmediate = Integer.parseInt(Environment.getInstance().getPropertyWithDefault("org.art.db.queueRecovery"));
		
		if (logs != null) {
			List<File> list = Arrays.asList(logs);
			
			Collections.sort(list, (a, b) -> a.getName().compareTo(b.getName()));
			
			List<File> recoverImmediately;
			List<File> deferred;
			
			if (this.metadata.isQueue() && list.size() > maxImmediate) {
				recoverImmediately = list.subList(0, maxImmediate);
				deferred = list.subList(maxImmediate, list.size());
			} else {
				recoverImmediately = list;
				deferred = Collections.emptyList();
			}

			for (File file : recoverImmediately) {
				if (file.length() != 0) {
					this.applySnapshot(new FileInputStream(file), false, file);
				} 
			}
			
			if (!deferred.isEmpty()) {
				logger.info("background " + list.size() + " def " + deferred.size() + " immediate " + recoverImmediately.size());
				Scheduler.DEFAULT.submit(() -> this.applyInBackground(deferred));
			}
		}
		
		this.checkConstraints = true;
	}
	
	private void applyInBackground(List<File> files) {
		for (File file : files) {
			if (file.length() != 0) {
				try {
					this.applySnapshot(new FileInputStream(file), false, file);
				} catch (Exception exc) {

				}
			} 
		}
	}
	
	public void applySnapshot(InputStream stream, boolean checkTimestamps, File file) {
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
						this.insert(entry.getDocument(), false, false);
						break;
					case REMOVE:
						this.remove(this.table.get(entry.getKey()), false, false);
						break;
					case UPDATE:
						this.update(this.table.get(entry.getKey()), entry.getDocument(), false, false);
						break;
					default:
						logger.info("bad op " + entry);
						break;
					}
				} catch (Exception exc) {
					Metrics.getInstance().logAlert("There was an error recovering a log entry");
					logger.info("There was an exception recovering a log entry " + exc.getMessage() + " " + entry + " check " + checkTimestamps);
					exc.printStackTrace();
				}
			}
		} catch (Exception exc) {
			Metrics.getInstance().logAlert("There was an error recovering the log");
			logger.info("There was an exception recovering the log " + exc.getMessage());
			exc.printStackTrace();
		} 
		
	}
	
	private Key buildKey(Document doc, List<String> names) {
		List<Comparable<?>> keys = new ArrayList<>();
		
		for (String name : names) {
			Comparable<?>  value = (Comparable<?>)doc.get(name);
			
			keys.add(value == null ? DBNull.NULL : value);
		}
		
		return new Key(keys);
	}
	
	private void checkForDuplicateKey(IndexInfo info, Document doc) throws Exception {		
		Index index = this.indices.get(info.name());
		
		Key indexKey = this.buildKey(doc, info.fields());
		
		this.alternateKeyLockManager.writeLock(new Key(info.name(), indexKey));
		
		if (index.get(indexKey) != null) {
			throw new DBRuntimeException("Duplicate value for " + info.name());
		}
	}
	
	public void checkWriteLock(Document doc) {
		this.primaryKeyLockManager.checkWriteLock(this.buildKey(doc, metadata.primaryKeyFields()));
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
	
	public Document getDocument(Key key, boolean lock) throws Exception {
		
		if (lock) {
			this.primaryKeyLockManager.writeLock(key);
		}
		
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
	
	public void insert(Document doc, boolean propagate, boolean log) throws Exception {
		try {
			Key primaryKey = this.buildKey(doc, this.metadata.primaryKeyFields());

			if (this.checkConstraints) {
				this.primaryKeyLockManager.writeLock(primaryKey);
				
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
			this.table.insert(primaryKey, doc, propagate, log);
			this.updateIndices(null, doc, primaryKey);
		} finally {
			this.alternateKeyLockManager.unlockAll();
		}
		
	}
	
	public boolean isQueue() {
		return this.metadata.isQueue();
	}
	
	public DocumentSet lease(String owner, long timeout) {
		if (!this.metadata.isQueue()) {
			throw new DBRuntimeException("The lease() function is only supported for queue tables");
		}
		return this.table.lease(owner, timeout);
	}
	
		public void remove(Document doc, boolean propagate, boolean log) throws Exception {
		Key primaryKey = this.buildKey(doc, this.metadata.primaryKeyFields());
		
		this.timestamps.put(primaryKey, System.currentTimeMillis());
		this.table.remove(primaryKey, propagate, log);
		this.updateIndices(doc, null, primaryKey);
	}
	
	public void mergeAll() {
		this.table.mergeAll();
	}
	
	public void unlockPrimaryKey(Key key) {
		this.primaryKeyLockManager.unlock(key);
	}
	
	public void unlockPrimaryKey(Document doc) {
		this.primaryKeyLockManager.unlock(this.buildKey(doc, this.metadata.primaryKeyFields()));
	}
	
	
	public void unlockAllPrimaryKeys(DocumentSet set) {
		for (Document doc : set) {
			unlockPrimaryKey(doc);
		}
	}
	
	public void update(Document oldDoc, Document newDoc, boolean propagate, boolean log) throws Exception {
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
			this.table.update(primaryKey, newDoc, propagate, log);			
			this.updateIndices(oldDoc, newDoc, primaryKey);
		} finally {
			this.alternateKeyLockManager.unlockAll();
		}
	}
	
	public void updateIndices(Document oldDoc, Document newDoc, Key primaryKey) throws Exception {

		for (int idx = 0; idx < this.indexInfo.size(); idx++) {
			IndexInfo info = this.indexInfo.get(idx);
			Index index = this.indices.get(info.name());
			
			Key oldKey = oldDoc == null ? null : this.buildKey(oldDoc, info.fields());
			Key newKey = newDoc == null || newDoc.isZombie() ? null : this.buildKey(newDoc, info.fields());

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
	
	public boolean isCheckConstraints() {
		return checkConstraints;
	}

	public void setCheckConstraints(boolean checkConstraints) {
		this.checkConstraints = checkConstraints;
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
		
		if (this.lock != null) {
			this.lock.release();
		}
		
	}
	
	public void crash() throws Exception {
		this.table.crash();
		
		if (this.lock != null) {
			this.lock.release();
		}

	}
}
