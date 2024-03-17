package org.art.db.storage;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import org.art.db.common.Key;

import java.util.Map.Entry;

public class Index implements Closeable, Iterable<Map.Entry<Key, Key>> {
	private final boolean unique;
	private final Tree<Key> tree;
			
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
		return this.tree.getAll(comparison, key);
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
		} else {		
			this.tree.put(key.with(value), value);
		} 
	}
	
	
	public void remove(Key key, Key value) throws Exception {
		if (this.unique) {
			this.tree.remove(key);
		} else {
			this.tree.remove(key.with(value));
		}
		
	}
	
}
