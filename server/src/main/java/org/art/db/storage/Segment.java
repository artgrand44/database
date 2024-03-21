package org.art.db.storage;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.art.db.common.Blob;
import org.art.db.common.Document;
import org.art.db.common.GUID;
import org.art.db.common.Key;
import org.art.db.common.TypedInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class Segment implements Iterable<Map.Entry<Key, Blob>>, Closeable {
	
	@SuppressWarnings("unused")
	private static final Logger logger = LoggerFactory.getLogger(Segment.class);
	
	private static final int MEMORY_BLOCK_SIZE = 8192;
	private static final int BLOOM_SIZE = 2048;
	
	static final int DISK_BLOCK_SIZE = 1024 * 1024;
	
	private final Set<Key> zombies = new HashSet<>();
	private final BitSet bloomFilter;
	private final File file;
	
	private Metadata metadata;
	private GUID highGUID;
	
	public Segment(String table) throws Exception {
		
		this.file = null;
		this.metadata = new Metadata("");
		this.bloomFilter = new BitSet(BLOOM_SIZE);
		this.highGUID = new GUID();
		this.metadata = Metadata.getMetadata(table);

	}
	
	public Segment(File file) throws Exception {
		logger.info("disk seg");
		this.file = file;
		this.metadata = new Metadata("");
		this.bloomFilter = new BitSet(BLOOM_SIZE);
		this.highGUID = new GUID();
	}
	
	public abstract Set<Document> getAll(Key key) throws Exception;
	
	@Override
	public void close() throws IOException {
	}
	
	public Document get(Key key) throws Exception {
		 Set<Document> set = this.getAll(key);
		 
		 return set.isEmpty() ? null : set.iterator().next();
	}
	
	protected int getHash(Key key) {
		return Math.abs(key.hashCode() % BLOOM_SIZE);
	}
	

	public abstract Iterator<Entry<Key, Blob>> iterator();

	
	public abstract long size();
	
	
	public Set<Key> getZombies() {
		return zombies;
	}

	
 	public BitSet getBloomFilter() {
		return bloomFilter;
	}

	public Metadata getMetadata() {
		return metadata;
	}

	public void setMetadata(Metadata metadata) {
		this.metadata = metadata;
	}

	public File getFile() {
		return file;
	}

	public GUID getHighGUID() {
		return highGUID;
	}

	public void setHighGUID(GUID highGUID) {
		this.highGUID = highGUID;
	}

	@Override
	public String toString() {
		return "Segment [file=" + file + ", highGUID=" + highGUID + "]";
	}

	
}

