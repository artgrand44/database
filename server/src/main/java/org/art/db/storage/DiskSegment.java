package org.art.db.storage;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.art.db.common.Blob;
import org.art.db.common.Document;
import org.art.db.common.Key;
import org.art.db.common.TypedInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DiskSegment extends Segment implements Iterable<Map.Entry<Key, Blob>>, Closeable {
	
	@SuppressWarnings("unused")
	private static final Logger logger = LoggerFactory.getLogger(DiskSegment.class);
	
	private static final int BLOOM_SIZE = 2048;
	
	static final int DISK_BLOCK_SIZE = 1024 * 1024;
	
	private final Set<Key> zombies = new HashSet<>();
	private final Tree<Blob> tree;
	private final TreeStore treeStore;
	private final File file;
	
	
	public DiskSegment(File file) throws Exception {
		super();
		
		this.file = file;
		this.tree = new Tree<>(file, DISK_BLOCK_SIZE, false, Blob.class);
		this.treeStore = tree.treeStore;
		
		this.setMetadata(this.treeStore.getMetadata());
		this.setHighGUID(this.treeStore.getHighGUID());
		this.setBloomFilter(this.treeStore.getBloomFilter());
		
		byte[] bytes = this.treeStore.getZombies();
		Set<Key> zombies = new HashSet<>();
		
		try (TypedInputStream stream = new TypedInputStream(bytes)) {
			int count = stream.readInt();
			
			for (int idx = 0; idx < count; idx++) {
				zombies.add(new Key().deserialize(stream));
			}
		}
		
		this.setZombies(zombies);
	}
	
	@Override
	public Document get(Key key) throws Exception {
		
		if (this.getBloomFilter().get(this.getHash(key))) {
			Blob blob = this.tree.get(Comparison.EQ, key);
			
			if (blob != null) {
				return BlobConverter.toDocument(this.getMetadata(), blob);
			}	
		}
		
		return null;
	}
	
	@Override
	public void close() throws IOException {
		this.tree.close();
	}
	
	
	public int getHash(Key key) {
		return Math.abs(key.hashCode() % BLOOM_SIZE);
	}
	

	@Override
	public Iterator<Entry<Key, Blob>> iterator() {
		return this.tree.iterator();
	}

	
	public long size() {
		return this.tree.size();
	}
	
	public Set<Key> getZombies() {
		return zombies;
	}

	
 	public File getFile() {
		return file;
	}

	
	@Override
	public String toString() {
		return "DiskSegment [" + file + "]";
	}

	
}

