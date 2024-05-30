package org.art.db.storage;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import org.art.db.common.Comparison;
import org.art.db.common.Key;

import java.util.Map.Entry;

public class Index implements Closeable, Iterable<Map.Entry<Key, Key>> {
	private final boolean unique;
	private final Tree<Key> tree;
			
	private static final IndexCache cache = new IndexCache();
	
	
	public Index(File file, boolean unique) throws Exception {
		this.unique = unique;
		this.tree = new Tree<>(file, Key.class);
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
		Set<Key> keys = this.getAllKeys(comparison, key);
		
		return keys.isEmpty() ? null :  keys.iterator().next();
	}
	
	public Set<Key> getAll(Comparison comparison, Key key) throws Exception {
		IndexKey indexKey = new IndexKey(this, key);
		Set<Key> keys;
		
		if (this.unique && comparison == Comparison.EQ && (keys = cache.getIfPresent(indexKey)) != null) {
			return keys;
		}
		
		keys = this.tree.getAll(comparison, key);
		
		if (this.unique && comparison == Comparison.EQ) {
			cache.put(indexKey, keys);
		}
		
		return keys;
	}
	
	public Set<Key> getAllBetween(Key first, Key last) throws Exception {
		return this.tree.getAllBetween(first, last);
	}
	
	public Set<Key> getAllKeys(Comparison comparison, Key key) throws Exception {
		return this.tree.getAllKeys(comparison, key);
	}
	
	public int getSelectivity() {
		return this.tree.getSelectivity();
	}
	
	public String getStats() {
		return this.tree.getStats();
	}
	
	@Override
	public Iterator<Entry<Key, Key>> iterator() {
		return this.tree.iterator();
	}

	public void put(Key key, Key value) throws Exception {
		if (this.unique) {
			this.tree.put(key, value);
			
			Set<Key> set = new HashSet<>();
			
			set.add(value);
			cache.put(new IndexKey(this, key), set);
		} else {		
			this.tree.put(key.with(value), value);
		} 
	}
	
	
	public void remove(Key key, Key value) throws Exception {
		if (this.unique) {
			this.tree.remove(key);
			cache.invalidate(new IndexKey(this, key));
		} else {
			this.tree.remove(key.with(value));
		}
		
	}
	
	static record IndexKey(Index index, Key key) {
		
	}
	
	static class IndexCache extends ConfigurableCache<IndexKey, Set<Key>> {
		IndexCache() {
			super("org.art.db.indexCache");
		}
	}
	
}
