package org.art.db.storage;

import java.io.Closeable;
import java.util.BitSet;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.art.db.common.Blob;
import org.art.db.common.Document;
import org.art.db.common.GUID;
import org.art.db.common.Key;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class Segment implements Iterable<Map.Entry<Key, Blob>>, Closeable {
	
	@SuppressWarnings("unused")
	private static final Logger logger = LoggerFactory.getLogger(Segment.class);
	
	static final int BLOOM_SIZE = 2048;
	static final int DISK_BLOCK_SIZE = 1024 * 1024;
	
	private Set<Key> zombies = new HashSet<>();
	private BitSet bloomFilter;
	
	private Metadata metadata;
	private GUID highGUID;
	
	public Segment() throws Exception {
		this.metadata = new Metadata("");
		this.bloomFilter = new BitSet(BLOOM_SIZE);
		this.highGUID = new GUID();
	}
	
	public abstract Set<Document> getAll(Key key) throws Exception;
	
	public Document get(Key key) throws Exception {
		 Set<Document> set = this.getAll(key);
		 
		 return set.isEmpty() ? null : set.iterator().next();
	}
	
	protected int getHash(Key key) {
		return Math.abs(key.hashCode() % BLOOM_SIZE);
	}
	

	public abstract Iterator<Entry<Key, Blob>> iterator();

	
	public abstract long size();
	
	
	public Metadata getMetadata() {
		return metadata;
	}

	public void setMetadata(Metadata metadata) {
		this.metadata = metadata;
	}

	

	public GUID getHighGUID() {
		return highGUID;
	}

	public void setHighGUID(GUID highGUID) {
		this.highGUID = highGUID;
	}

	@Override
	public String toString() {
		return "Segment []";
	}

	public Set<Key> getZombies() {
		return zombies;
	}

	public void setZombies(Set<Key> zombies) {
		this.zombies = zombies;
	}

	public BitSet getBloomFilter() {
		return bloomFilter;
	}

	public void setBloomFilter(BitSet bloomFilter) {
		this.bloomFilter = bloomFilter;
	}

	
}

