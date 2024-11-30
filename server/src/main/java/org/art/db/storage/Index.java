package org.art.db.storage;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.art.db.common.Blob;
import org.art.db.common.Comparison;
import org.art.db.common.DBNull;
import org.art.db.common.Document;
import org.art.db.common.Key;
import org.art.db.common.Scheduler;
import org.art.db.common.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Index implements Closeable, Iterable<Map.Entry<Key, Key>> {
	
	private static final Logger logger = LoggerFactory.getLogger(Index.class);
	
	/**
	 * Is this a unique index?
	 */
	private final boolean unique;
	
	/**
	 * The name of the index
	 */
	private final String name;
	
	/**
	 * The underlying tree
	 */
	private final Tree<Key> tree;
	
	/**
	 * A persistent "set" containing of the primary keys that
	 * have been processed during a buildIndex operation
	 */
	private Tree<Boolean> processed;
	
	/**
	 * Are we building the index?
	 */
	private volatile boolean building;
			
	private static final IndexCache cache = new IndexCache();
	
	
	public Index(File file, String name, boolean unique) throws Exception {
		this.name = name;
		this.unique = unique;
		this.tree = new Tree<>(file, Key.class);
	}
	
	public void buildIndex(Table table, Metadata metadata)  {
		
		if (table.isEmpty()) {
			try {
				IndexInfo info = metadata.getIndex(this.name);

				info.setBuilt(true);
				metadata.store();
			} catch (Exception exc) {
				logger.error("There was an exception building the index for " + this.name + exc.getMessage());
				MetricsLogger.getInstance().logAlert("There was an exception building the index for " + this.name);
			}
			
			return;
		}
			
		logger.info("**** submitting " + this.name);
		Scheduler.DEFAULT.submit(() -> this.buildIndexInBackground(table, metadata));
		
	}
	
	private void buildIndexInBackground(Table table, Metadata metadata) {
		
		IndexInfo info = metadata.getIndex(this.name);
		File inverseFile = DBFiles.getInverseIndex(metadata.getName(), info.getName());
		
		try {
			this.processed = new Tree<>(inverseFile, Boolean.class);
			this.building = true;
			
			for (Segment segment : table.getSegments()) {
				Iterator<Map.Entry<Key, Blob>> iterator = segment.iterator();

				while (iterator.hasNext()) {
					Map.Entry<Key, Blob> entry = iterator.next();
					Key primaryKey = entry.getKey();
					Document doc = BlobConverter.toDocument(metadata, entry.getValue());

					if (processed.get(Comparison.EQ, primaryKey) == null) {
						Key key = this.buildKey(doc, info.getFields());
					
						this.put(key, primaryKey);
						processed.put(primaryKey, Boolean.TRUE);
					}
				}
			}
			
			info.setBuilt(true);
			metadata.store();
			logger.info("Set built");
		} catch (Exception exc) {
			logger.error("There was an exception building the index for " + this.name + exc.getMessage());
			MetricsLogger.getInstance().logAlert("There was an exception building the index for " + this.name);
		} finally {
			this.building = false;
			Utils.closeSafely(this.processed);
			this.processed = null;
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

	@Override
	public void close() throws IOException {
		this.tree.close();
		
	}
	
	public void flush() {
		this.tree.flushAll();
	}
	
	public Key get(Key key) throws Exception {
		return this.get(Comparison.EQ, key);
	}
	
	public Key get(Comparison comparison, Key key) throws Exception {
		List<Key> keys = this.getAllKeys(comparison, key);
		
		return keys.isEmpty() ? null :  keys.getFirst();
	}
	
	public List<Key> getAll(Comparison comparison, Key key) throws Exception {
		IndexKey indexKey = new IndexKey(this, key);
		List<Key> keys;
		
		if (this.unique && comparison == Comparison.EQ && (keys = cache.getIfPresent(indexKey)) != null) {
			return keys;
		}
		
		keys = this.tree.getAll(comparison, key);
		
		if (this.unique && comparison == Comparison.EQ) {
			cache.put(indexKey, keys);
		}
		
		return keys;
	}
	
	public List<Key> getAllBetween(Key first, Key last) throws Exception {
		return this.tree.getAllBetween(first, last);
	}
	
	public List<Key> getAllKeys(Comparison comparison, Key key) throws Exception {
		return this.tree.getAllKeys(comparison, key);
	}
	
	public int getSelectivity() {
		return this.tree.getSelectivity();
	}
	
	
	@Override
	public Iterator<Entry<Key, Key>> iterator() {
		return this.tree.iterator();
	}
	
	private void markUpdated(Key primaryKey) throws Exception {
		if (this.building) {
			this.processed.put(primaryKey, true);
		}
	}

	public void put(Key key, Key value) throws Exception {
		this.markUpdated(value);
		
		if (this.unique) {
			this.tree.put(key, value);
			
			List<Key> set = new LinkedList<>();
			
			set.add(value);
			cache.put(new IndexKey(this, key), set);
		} else {		
			this.tree.put(key.with(value), value);
		} 
	}
	
	
	public void remove(Key key, Key value) throws Exception {
		this.markUpdated(value);
		
		if (this.unique) {
			this.tree.remove(key);
			cache.invalidate(new IndexKey(this, key));
		} else {
			this.tree.remove(key.with(value));
		}
		
	}
	
	static record IndexKey(Index index, Key key) {
		
	}
	
	static class IndexCache extends ConfigurableCache<IndexKey, List<Key>> {
		IndexCache() {
			super("org.art.db.indexCache");
		}
	}
	
}
