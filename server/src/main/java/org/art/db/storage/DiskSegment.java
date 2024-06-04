package org.art.db.storage;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;

import org.art.db.common.Blob;
import org.art.db.common.Comparison;
import org.art.db.common.Document;
import org.art.db.common.Key;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DiskSegment extends Segment implements Iterable<Map.Entry<Key, Blob>>, Closeable {
	
	private static final Logger logger = LoggerFactory.getLogger(DiskSegment.class);
	
	private static final int BLOOM_SIZE = 2048;
	
	static final int DISK_BLOCK_SIZE = 1024 * 1024;
	
	private final Tree<Blob> tree;
	private final TreeStore treeStore;
	private final File file;
	
	
	public DiskSegment(File file) throws Exception {
		super();

		try {
			this.file = file;
			this.tree = new Tree<>(file, DISK_BLOCK_SIZE, Blob.class);
			this.treeStore = tree.treeStore;

			this.setMetadata(this.treeStore.getMetadata());
			this.setHighGUID(this.treeStore.getHighGUID());
			this.setHighZombe(this.treeStore.getHighZombie());
			this.setLowGUID(this.treeStore.getLowGUID());
			this.setBloomFilter(this.treeStore.getBloomFilter());
		} catch (Exception exc) {
			Metrics.getInstance().logAlert("There was an error opening the database");
			logger.info("Got exception on file " + file.getAbsolutePath() + " " + exc.getMessage());
			exc.printStackTrace();
			throw exc;
		}
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
	
	public long getCreateTime() {
		return this.treeStore.getCreateTime();
	}
	

 	public File getFile() {
		return file;
	}

 	public int getMergeLevel() {
 		return this.treeStore.getMergeLevel();
 	}
 	
 	public void setMergeLevel(int createLevel) throws Exception {
 		
 	}
	@Override
	public String toString() {
		return "DiskSegment [" + file + "]";
	}

	
}

