package org.art.db.storage;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.BitSet;
import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.art.db.common.Blob;
import org.art.db.common.Document;
import org.art.db.common.Key;
import org.art.db.common.TypedOutputStream;
import org.art.db.storage.TreeStore.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SegmentBuilder {
	
	private static final Logger logger = LoggerFactory.getLogger(SegmentBuilder.class);
	
	
	
	
	/**
	 * Build a disk-based DataFile from an in-memory DataFile
	 * @param segment The DataFile
	 * @param toFile The file to write it to
	 * @param diskBlockSize The block size
	 * @throws Exception
	 */
	static void build(Segment segment, File toFile, int diskBlockSize) throws Exception {
		build(toFile, segment.iterator(), segment.getMetadata(), segment.getBloomFilter(), segment.getZombies(), diskBlockSize);
	}
	
	
	/**Build a DataFile.  
	 * @param toFile
	 * @param leafNodes
	 * @param bloomFilter
	 * @param zombies
	 * @param diskBlockSize
	 * @throws Exception
	 */
	@SuppressWarnings({ "rawtypes", "unchecked" })
	private static void build(File segment, Iterator leafNodes, Metadata metadata, BitSet bloomFilter, Set<Key> zombies, int diskBlockSize) throws Exception {
		
		Header header = new Header();
		
		// Write out the nodes using a buffered output steam with a big buffer size.
		// This should be considerably faster than using a BlockFile.
		
		File tempFile = File.createTempFile("segment", "tmp", segment.getParentFile());
		Key highKey = null;
		int count = 0;
		
		try (FileOutputStream fstream = new FileOutputStream(tempFile); 
				BufferedOutputStream bstream = new BufferedOutputStream(fstream, 10 * 1024); 
				TypedOutputStream stream = new TypedOutputStream(bstream)) {
			
			// Advance past the header
			
			stream.write(new byte[diskBlockSize]);
			
			// Iterate over the leaves in order or ascending key.  Build the leaves (using a possibly larger
			// block size that the original tree) and build a map from the first key in each node to the block
			// number of the node. When the process is completed, iterate over the map and build the parent nodes.
			// This process continues until we write out the root.
			
			
			Iterator iterator = leafNodes;
			TreeMap<Key, Long> parents = new TreeMap<>();
			
			TreeNode node = null;
			boolean isLeaf = true;
			long startBlock = 1;
			int maxBlobSize = (diskBlockSize) / 3 * 2;
			
			for (;;) {
				while (iterator.hasNext()) {
					
					// Get the next key/value pair from the original tree and determine it's size
					
					Map.Entry<Key, ? extends Object> entry = (Map.Entry<Key, ? extends Object>)iterator.next();
					Key key = entry.getKey();
					Object value = entry.getValue();
					int size = key.getSize() + TypedOutputStream.getSize(value);
					
					count++;

					if (isLeaf) {
						highKey = key;
					}
					
					// If the value is a big Blob then break it into chunks and
					// set value to the disk-based chunk
					
					if (value instanceof Blob blob && blob.getSize() > maxBlobSize) {
						AtomicInteger nblocks = new AtomicInteger();
						
						value = blob.toStream(stream, startBlock, diskBlockSize, nblocks);
						startBlock += nblocks.get();
					}
					
					// If the key/value pair will fit in the current node then add it
					// to the node.  Otherwise write out the current node, create
					// a new node, and put the key/value pair in the new node.
					
					if (node != null && node.storageSize() + size < diskBlockSize) {
						node.addEntry(key, value);
					} else {
						if (node != null) {
							parents.put(node.firstKey(), node.getId());
							
							if (iterator.hasNext()) {
								node.setNext(startBlock);
							}
							
							writeBlock(stream, node.serialize(), diskBlockSize);
						}

						node = new TreeNode(isLeaf, startBlock++);
						
						node.addEntry(key, value);
					}
				}
				
				// Write out the last node for this level of the tree (if there is one)
				
				if (node != null) {
					parents.put(node.firstKey(), node.getId());
					writeBlock(stream, node.serialize(), diskBlockSize);
				}
				
				// If there is only one parent node then save the block number in
				// the header and exit the loop.  Otherwise loop over the parents.
				
				if (parents.size() == 1) {
					header.root = parents.values().iterator().next();
					break;
				} else if (parents.size() == 0) {
					// Saving empty segment
					
					break;
				} else {
					iterator = parents.entrySet().iterator();
					parents = new TreeMap<>();
					isLeaf = false;
					node = null;
				}
			}

		}
		
		// Write out the header.
		
		try (BlockFile blockFile = new BlockFile(tempFile, diskBlockSize)) {
			blockFile.writeBlock(header.serialize(), 0);
		}
		
		// Write out the bloom filter, zombies, etc
		
		try (TreeStore store = new TreeStore(tempFile, diskBlockSize)) {
			store.setBloomFilter(bloomFilter);
			store.setZombies(zombies);
			store.setMetadata(metadata);
			
			String name = segment.getName();
			int dash = name.indexOf('_');
			int dot = name.indexOf('.');
			long createTime = Long.parseLong(name.substring(0, dash));
			int mergeLevel = Integer.parseInt(name.substring(dash + 1, dot));
			
			store.setCreateTime(createTime);
			store.setMergeLevel(mergeLevel);
			
			if (highKey == null) {
				logger.info("no high key " + count);
			}
			if (highKey != null && highKey.isGUID()) {
				store.setHighGUID(highKey.getGUID());
			}
		}
				
		tempFile.renameTo(segment);
	}
	
	static void merge(File toFile, int diskBlockSize, File[] fromFiles) throws Exception {
		DiskSegment[] segments = new DiskSegment[fromFiles.length];
		BitSet bloomFilter = new BitSet();
		
		for (int idx = 0; idx < fromFiles.length; idx++) {
			segments[idx] = new DiskSegment(fromFiles[idx]);
			bloomFilter.or(segments[idx].getBloomFilter());
		}
		
		build(toFile, new Merger(segments), segments[0].getMetadata(), bloomFilter, Collections.emptySet(), diskBlockSize);
		
		for (Segment segment : segments) {
			segment.close();
		}
	
		
	}
	private static void writeBlock(TypedOutputStream stream, byte[] bytes, int blockSize) throws IOException {
		stream.write(bytes);
		
		if (bytes.length < blockSize) {
			stream.write(new byte[blockSize - bytes.length]);
		}
	}

	static class Merger implements Iterator<Entry<Key, Blob>> {
		private final Iterator<Entry<Key, Blob>>[] iterators;
		private final Metadata[] metadata;
		private final boolean[] needsValue;
		
		private Entry<Key, Blob>[] currentValue;
		
		@SuppressWarnings("unchecked")
		Merger(Segment[] segments) {
			this.iterators = new Iterator[segments.length];
			this.metadata = new Metadata[segments.length];
			this.needsValue = new boolean[iterators.length];
			this.currentValue = new Entry[iterators.length];
			
			for (int idx = 0; idx < segments.length; idx++) {
				this.iterators[idx] = segments[idx].iterator();
				this.metadata[idx] = segments[idx].getMetadata();
				this.needsValue[idx] = true;
			}
		}
		
		@Override
		public boolean hasNext() {
			for (Iterator<Entry<Key, Blob>> iter : this.iterators) {
				if (iter.hasNext()) {
					return true;
				}
			}
			
			return false;
		}

		@Override
		public Entry<Key, Blob> next() {
			Entry<Key, Blob> bestValue = null;
			int bestIndex = -1;
			
			for (int idx = 0; idx < this.iterators.length; idx++) {
				if (this.needsValue[idx]) {
					this.currentValue[idx] = this.iterators[idx].hasNext() ? this.iterators[idx].next() : null;
					this.needsValue[idx] = false;				
				}
				
				Entry<Key, Blob> thisValue = this.currentValue[idx];
				
				if (thisValue != null) {
					
					if (bestValue == null) {
						bestValue = thisValue;
						bestIndex = idx;
					} else if (thisValue.getKey().compareTo(bestValue.getKey()) < 0){
						bestValue = thisValue;
						bestIndex = idx;
					}
				}
			}
			
			for (int idx = 0; idx < this.iterators.length; idx++) {
				if (bestValue.equals(this.currentValue[idx])) {
					this.needsValue[idx] = true;
				}
			}
			
			if (this.metadata[bestIndex].equals(this.metadata[0])) {
				return bestValue;
			}
			
			try {
				Document doc = BlobConverter.toDocument(this.metadata[bestIndex], bestValue.getValue());
				Blob blob = BlobConverter.fromDocument(this.metadata[0], doc);
				
				return new SimpleImmutableEntry<>(bestValue.getKey(), blob);
				
			} catch (Exception exc) {
				throw new IllegalStateException("A document could not be reformatted", exc);
			}
			
		}

	}

}
