package org.art.db.storage;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.art.db.common.Comparison;
import org.art.db.common.Key;
import org.art.db.common.Scheduler;
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
			
		Scheduler.DEFAULT.submit(() -> this.buildIndexInBackground(table, metadata));
		
	}
	
	private void buildIndexInBackground(Table table, Metadata metadata) {
		
		try {
			IndexBuilder.build(table, this, metadata);
		} catch (Exception exc) {
			logger.error("There was an exception building the index for " + this.name + exc.getMessage());
			MetricsLogger.getInstance().logAlert("There was an exception building the index for " + this.name);
		} 	
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
	

	public void insert(Key key, Key primaryKey) throws Exception {
		if (this.unique) {
			this.tree.put(key, primaryKey);
			
			List<Key> set = new LinkedList<>();
			
			set.add(primaryKey);
			cache.put(new IndexKey(this, key), set);
		} else {		
			this.tree.put(key.with(primaryKey), primaryKey);
		} 
	}
	
	
	public void remove(Key key, Key primaryKey) throws Exception {
		if (this.unique) {
			this.tree.remove(key);
			cache.invalidate(new IndexKey(this, key));
		} else {
			this.tree.remove(key.with(primaryKey));
		}
		
	}
	
	public void update(Key oldKey, Key newKey, Key primaryKey) throws Exception {
		if (!oldKey.equals(newKey)) {
			this.remove(oldKey, primaryKey);
			this.insert(newKey, primaryKey);
		}
	}
	
	static record IndexKey(Index index, Key key) {
		
	}
	
	static class IndexCache extends ConfigurableCache<IndexKey, List<Key>> {
		IndexCache() {
			super("org.art.db.indexCache");
		}
	}

	public String getName() {
		return name;
	}
	
}
