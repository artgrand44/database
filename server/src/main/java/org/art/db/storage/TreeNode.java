package org.art.db.storage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import org.art.db.common.Key;
import org.art.db.common.TypedInputStream;
import org.art.db.common.TypedOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class TreeNode {
	
	private static final Logger logger = LoggerFactory.getLogger(TreeNode.class);
	
	private List<Entry> entries = new ArrayList<>();
	private boolean isLeaf;
	private long next;
	private long id;
	private int storageSize;
	
	
	private boolean modified;

	TreeNode() {
		
	}
	
	TreeNode(boolean isLeaf, long id) {
		this.isLeaf = isLeaf;
		this.id = id;
		this.next = -1;
		this.modified = true;
		
		this.computeStorageSize();
	}
	
	TreeNode(TreeNode that) {
		this.isLeaf = that.isLeaf;
		this.id = that.id;
		this.next = that.next;
		this.modified = that.modified;
		this.storageSize = that.storageSize;
		
		for (Entry entry : that.entries) {
			this.entries.add(entry);
		}
	}
	
	public TreeNode(byte[] bytes) throws Exception {
		try (TypedInputStream stream = new TypedInputStream(bytes) ) {
			this.isLeaf = stream.readBoolean();
			this.id = stream.readLong();
			this.next = stream.readLong();
			
			int count = stream.readInt();
			
			for (int idx = 0; idx < count; idx++) {
				Key key = (Key)stream.readObject();
				Object value = stream.readObject();
				
				Objects.requireNonNull(key);
				Objects.requireNonNull(value);
				
				this.entries.add(new Entry(key, value));
			}
			
			this.computeStorageSize();
		}
	}
	
	void addEntry(int insertionPoint, Key key, Object value) {
		Objects.requireNonNull(key);
		Objects.requireNonNull(value);
		
		this.entries.add(insertionPoint, new Entry(key, value));
		this.modified = true;
		
		this.computeStorageSize();
	}
	
	void addEntry(Key key, Object value) {
		if (key == null) {
			logger.info("oops");
		}
		Objects.requireNonNull(key);
		Objects.requireNonNull(value);
		
		this.entries.add(new Entry(key, value));
		this.modified = true;
		
		this.computeStorageSize();
	}
	
	int binarySearch(Key key) {
		try {
			return Collections.binarySearch(this.entries, new Entry(key, null));
		} catch (Exception exc) {
			for (Entry entry : this.entries) {
				if (entry == null) {
					logger.info("null entry");
				} else if (entry.key == null) {
					logger.info("null key");
				}
			}
			
			try {
				int index = Collections.binarySearch(this.entries, new Entry(key, null));
				logger.info("second time worked!!!!!!!!");
				return index;
			} catch (Exception exc1) {
				logger.info("second exception " + exc1.getMessage());
			}
			throw exc;
		}
	}
	
	Key firstKey() {
		return this.entries.isEmpty() ? (Key)null : (Key)this.entries.get(0).key;
	}
	
	
	int getChildIndex(Key key) {
		try {
			int index = this.binarySearch(key);
			
			return index >= 0 ? index : Math.max(0,Math.abs(index) - 2);		
		} catch (Exception exc) {
			logger.info("getChildException key " + key + " node = " + this + " " + exc.getMessage());
			throw new IllegalStateException(exc);
		}
	}
	
	public static int getEntrySize(Key key, Object value) {
		return new Entry(key, value).getSize();
	}
	
	public long getId() {
		return id;
	}
	

	int getInsertionPoint(Key key) {
		try {
			int index = this.binarySearch(key);
			
			return index >= 0 ? index : Math.max(0, Math.abs(index) - 1);
		} catch (Exception exc) {
			logger.info("getChildException key " + key + " node = " + this + " " + exc.getMessage());
			throw new IllegalStateException(exc);
		}
	}
	
	Key getKey(int idx) {
		return idx >= 0 && idx < this.entries.size() ? this.entries.get(idx).key : null;
	}
	
	public long getNext() {
		return next;
	}
	
	Object getValue(int idx) {
		return idx >= 0 && idx < this.entries.size() ? this.entries.get(idx).value : null;
	}
	
	List<Object> getValues() {
		List<Object> result = new ArrayList<>();
		
		for (Entry entry : this.entries) {
			result.add(entry.value);
		}
		
		return result;
	}
	
	boolean isEmpty() {
		return this.entries.isEmpty();
	}
	boolean isLeaf() {
		return this.isLeaf;
	}
	
	public boolean isModified() {
		return modified;
	}

	void markClean() {
		this.modified = false;
	}
	
	void markModified() {
		this.modified = true;
		this.computeStorageSize();
	}
	
	void merge(TreeNode nextChild) {
		this.entries.addAll(nextChild.entries);
		
		for (Entry entry : this.entries) {
			Objects.requireNonNull(entry);
		}
		this.next = nextChild.next;	
		this.modified = true;
		
		this.computeStorageSize();
	}
	
	void remove(int idx) {
		this.entries.remove(idx);
		this.modified = true;
		
		this.computeStorageSize();
	}
	
	public void setId(long id) {
		this.id = id;
	}
	
	void setKey(int idx, Key key) {
		Entry old = this.entries.get(idx);
		
		this.entries.set(idx, new Entry(key, old.value));
		this.modified = true;
	}
	
	public void setNext(long next) {
		this.next = next;
		this.modified = true;
	}	
	
	void setValue(int idx, Object value) {
		Entry old = this.entries.get(idx);
		if (value == null) {
			logger.info("setting null value");
		}
		this.entries.set(idx, new Entry(old.key, value));
		this.modified = true;
		
		this.computeStorageSize();
	}

	int size() {
		return this.entries.size();
	}

	private void computeStorageSize() {
		int size = 21;
		
		for (Entry entry : this.entries) {
			if (entry == null) {
				logger.info("null entry");
			}
			size += entry.getSize();
		}
		
		this.storageSize = size;
	}
	int storageSize() {
		return this.storageSize;
	}
	
	public byte[] serialize() throws Exception {
		
		try (TypedOutputStream stream = new TypedOutputStream()) {
			stream.writeBoolean(this.isLeaf);
			stream.writeLong(this.id);
			stream.writeLong(this.next);
			stream.writeInt(this.entries.size());
			
			for (Entry entry : this.entries) {
				stream.writeObject(entry.key);
				stream.writeObject(entry.value);
			}
			
			return stream.toByteArray();
		} 
		
	}
	
	
	TreeNode split(long newId) {
		int split = this.entries.size() / 2;
		
		TreeNode newNode = new TreeNode(this.isLeaf(), newId);
		
		for (int idx = split + 1; idx < this.entries.size(); idx++) {
			Objects.requireNonNull(this.entries.get(idx));
			newNode.entries.add(this.entries.get(idx));
		}
		
		for (Entry entry : newNode.entries) {
			Objects.requireNonNull(entry);
			this.entries.remove(entry);
		}

		newNode.next = this.next;
		this.next = newNode.id;
		this.modified = true;
		
		this.computeStorageSize();
		
		return newNode;

	}

	@Override
	public String toString() {
		String first = this.entries.isEmpty() ? "" :  (this.entries.get(0).key.toString());
		String last = this.entries.isEmpty() ? "" :  this.entries.get(this.entries.size() - 1).key.toString();
		
		return "Node [id=" + id + ", isLeaf=" + isLeaf +  ", count = " + entries.size() + " next = " + next + ", first = " + first + ", last = " + last + "]";
	}

	static class Entry implements Comparable<Entry> {

		private Key key;
		private Object value;
		private int size = 0;
		
		Entry() {
			
		}
		
		Entry(Key key, Object value) {
			this.key = key;
			this.value = value;
			this.size = key.getSize() + TypedOutputStream.getSize(value) + 2;
		}
		
		@Override
		public int compareTo(Entry that) {
			return this.key.compareTo(that.key);
		}
	
		

		@Override
		public String toString() {
			return "Entry [" + key + "]";
		}

		public int getSize() {
			return size;
		}

		public void setSize(int size) {
			this.size = size;
		}
		
	}
	
}

