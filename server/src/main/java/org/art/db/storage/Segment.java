package org.art.db.storage;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.AbstractMap.SimpleEntry;
import java.util.concurrent.ConcurrentHashMap;

import org.art.db.common.Blob;
import org.art.db.common.Document;
import org.art.db.common.GUID;
import org.art.db.common.Key;
import org.art.db.common.TypedInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Segment implements Iterable<Map.Entry<Key, Blob>>, Closeable {
	
	@SuppressWarnings("unused")
	private static final Logger logger = LoggerFactory.getLogger(Segment.class);
	
	private static final int MEMORY_BLOCK_SIZE = 8192;
	private static final int BLOOM_SIZE = 2048;
	
	static final int DISK_BLOCK_SIZE = 1024 * 1024;
	
	private final Set<Key> zombies = new HashSet<>();
	private final Tree<Blob> tree;
	private final TreeStore treeStore;
	private final BitSet bloomFilter;
	private final ConcurrentHashMap<Key, Document> documents;
	private final File file;
	
	private Metadata metadata;
	private GUID highGUID;
	
	public Segment(String table) throws Exception {
		
		this.file = null;
		this.metadata = new Metadata("");
		this.bloomFilter = new BitSet(BLOOM_SIZE);
		this.highGUID = new GUID();
		this.metadata = Metadata.getMetadata(table);
		this.tree = new Tree<>(File.createTempFile("data", ".dat"), MEMORY_BLOCK_SIZE, true, Blob.class);
		this.treeStore = this.tree.treeStore;
		this.documents = new ConcurrentHashMap<>(10000);
	}
	
	public Segment(File file) throws Exception {
		logger.info("disk seg");
		this.file = file;
		this.tree = new Tree<>(file, DISK_BLOCK_SIZE, false, Blob.class);
		this.treeStore = tree.treeStore;
		this.documents = null;
		this.metadata = this.treeStore.getMetadata();
		this.highGUID = this.treeStore.getHighGUID();
		byte[] bytes = this.treeStore.getZombies();
		
		try (TypedInputStream stream = new TypedInputStream(bytes)) {
			int count = stream.readInt();
			
			for (int idx = 0; idx < count; idx++) {
				this.zombies.add(new Key().deserialize(stream));
			}
		}
		
		this.bloomFilter = this.treeStore.getBloomFilter();
	}
	
	public Set<Document> getAll(Key key) throws Exception {
		Document doc;
		
		if (this.documents != null && (doc = this.documents.get(key)) != null) {
			Set<Document> result = new HashSet<>();
			
			result.add(doc);
			return result;
		}
		
		if (this.bloomFilter.get(this.getHash(key))) {
			Set<Blob> blobs = this.tree.getAll(Comparison.EQ, key);
			Set<Document> result = new HashSet<>();
			
			for (Blob blob : blobs) {
				doc = BlobConverter.toDocument(this.metadata, blob);
				
				blob.setDocument(doc);
				result.add(doc);
			}
			
			return result;		
		}
		
		return Collections.emptySet();
	}
	
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
	

	@Override
	public Iterator<Entry<Key, Blob>> iterator() {
		if (this.documents != null) {
			return new SortedIterator(this.documents);
		}
		
		return this.tree.iterator();
	}

	public void insert(Key key, Document doc, boolean propagate) throws Exception {
		Blob blob = BlobConverter.fromDocument(metadata, doc);
		
		blob.setDocument(doc);
		
		if (this.documents == null) {
			this.tree.put(key, blob);
		} else {
			this.documents.put(key, doc);
		}
		
		this.bloomFilter.set(this.getHash(key));
		
		this.zombies.remove(key);
		
		GUID guid;
		
		if (key.isGUID() && (guid = key.getGUID()).compareTo(this.highGUID) > 0) {
			this.highGUID = guid;
		}
	}
	
	public void remove(Key key, boolean propagate) throws Exception {
		this.tree.remove(key);
		this.zombies.add(key);
	}
	
	public long size() {
		return this.documents == null ? this.tree.size() : this.documents.size();
	}
	
	public void update(Key key, Document doc, boolean propagate) throws Exception {
		
		if (this.documents == null) {
			Blob blob = BlobConverter.fromDocument(this.metadata, doc);
			
			blob.setDocument(doc);
			
			this.tree.put(key, blob);
		} else {
			this.documents.put(key, doc);
		}
		
		this.bloomFilter.set(this.getHash(key));
		
		this.zombies.remove(key);
		
		GUID guid;
		
		if (key.isGUID() && (guid = key.getGUID()).compareTo(this.highGUID) > 0) {
			this.highGUID = guid;
		}
	}
	
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

	class SortedIterator implements Iterator<Map.Entry<Key, Blob>> {
		private final Map<Key, Document> map;
		private final List<Key> sortedKeys;
		private final Iterator<Key> iterator;
		
		SortedIterator(Map<Key, Document> map) {
			this.map = map;
			this.sortedKeys = new ArrayList<>(map.size());
			
			this.sortedKeys.addAll(map.keySet());
			
			Collections.sort(this.sortedKeys);
			
			this.iterator = this.sortedKeys.iterator();
		}
		
		@Override
		public boolean hasNext() {
			return this.iterator.hasNext();
		}

		@Override
		public Entry<Key, Blob> next() {
			try {
				Key key = this.iterator.next();
				Blob blob = BlobConverter.fromDocument(metadata, this.map.get(key));
			
				return new SimpleEntry<>(key, blob);
			} catch (Exception exc) {
				throw new IllegalStateException(exc);
			}
		}
		
	}

}

