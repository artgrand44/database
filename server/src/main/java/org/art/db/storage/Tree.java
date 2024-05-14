package org.art.db.storage;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import org.art.db.common.Blob;
import org.art.db.common.Key;
import org.art.db.storage.LockManager.UpgradeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Tree<TValue> implements Closeable, Iterable<Map.Entry<Key, TValue>> {

	private static final Logger logger = LoggerFactory.getLogger(Tree.class);

	/**
	 * The root of the tree
	 */
	private TreeNode root;

	/**
	 * A nominal node used to synchronize access to this.node.
	 */
	private TreeNode guardNode = new TreeNode(false, -1);

	/**
	 * The file store for reading and writing the tree
	 */
	protected final TreeStore treeStore;

	/**
	 * The file name (for trace code)
	 */
	@SuppressWarnings("unused")
	private final String name;

	/**
	 * The maximum size of each node
	 */
	private final int nodeSize;

	/**
	 * The approximate size of the tree. Valid only for in memory trees.
	 */
	private final AtomicLong size = new AtomicLong();

	/**
	 * The total number of searches over the lifetime of the index
	 */
	private final AtomicLong getCount = new AtomicLong();

	/**
	 * The total number of puts over the lifetime of the index
	 */
	private final AtomicLong putCount = new AtomicLong();

	/**
	 * The total number of removes over the lifetime of the index
	 */
	private final AtomicLong removeCount = new AtomicLong();

	/**
	 * The last time the statistics were saved on dish
	 */
	private long lastStatsTime = System.currentTimeMillis();

	/**
	 * The total number of searches over the lifetime of the index
	 */
	private final AtomicLong resultCount = new AtomicLong();

	private final LockManager<Long> lockManager = new LockManager<>();

	private static boolean TRACE = false;

	private final static ThreadLocal<StringBuilder> logs = ThreadLocal.withInitial(() -> new StringBuilder());

	static record Visit(String reason, TreeNode node) {

	}

	private final static ThreadLocal<List<Visit>> visits = ThreadLocal.withInitial(() -> new ArrayList<>());

	public Tree(Class<TValue> clazz) throws Exception {
		this(File.createTempFile("tree", ".dat"), 2048, clazz);
	}

	public Tree(File file, Class<TValue> clazz) throws Exception {
		this(file, 8192, clazz);
	}

	public Tree(File file, int nodeSize, Class<TValue> clazz) throws Exception {
		this.treeStore = new TreeStore(file, nodeSize);
		this.nodeSize = this.treeStore.getNodeSize();
		this.name = file.getName();

		long rootId = this.treeStore.getRoot();

		this.lockManager.writeLock(rootId);

		if (rootId == -1) {
			this.root = this.createNode(true);
			this.saveNode(this.root);

			this.treeStore.saveRoot(root.getId());
		} else {
			this.root = this.getNode(rootId);
			this.getCount.set(this.treeStore.getSearchCount());
			this.resultCount.set(this.treeStore.getResultCount());
		}

		this.commit();
	}

	private boolean canMerge(TreeNode node1, TreeNode node2) {
		int size1 = node1.storageSize();
		int size2 = node2.storageSize();

		return size1 == 0 || size2 == 0 || (size1 + size2) < (this.nodeSize * 3) / 4;
	}

	public void close() throws IOException {
		this.treeStore.close();
		this.lockManager.stop();
	}

	private void checkReadLock(TreeNode node) {
		this.lockManager.checkReadLock(node.getId());
	}

	private void checkWriteLock(TreeNode node) {
		this.lockManager.checkWriteLock(node.getId());
	}

	private boolean commit() throws Exception {
		this.treeStore.commit();
		return this.lockManager.unlockAll();
	}

	public void crash() throws IOException {
		this.treeStore.crash();
		this.lockManager.stop();
	}

	private TreeNode createNode(boolean isLeaf) throws Exception {
		return this.treeStore.createNode(isLeaf);
	}

	public void dump() throws Exception {
		StringBuilder builder = new StringBuilder();

		this.dump(builder, this.root, "");

		logger.info(builder.toString());
	}

	private void dump(StringBuilder builder, TreeNode node, String indent) throws Exception {
		builder.append(indent).append(node).append("\n");

		if (!node.isLeaf()) {
			for (Object value : node.getValues()) {
				Long uuid = (Long) value;
				TreeNode child = this.getNode(uuid);

				this.dump(builder, child, "  " + indent);

				builder.append("\n");
			}
		}
	}

	public void flushAll() {

		this.treeStore.flushAll();
	}

	public TValue get(Comparison comparison, Key key) throws Exception {
		Set<TValue> set = this.getAll(comparison, key);

		return set.isEmpty() ? null : set.iterator().next();
	}

	public Set<TValue> getAll(Comparison comparison, Key key) throws Exception {
		try {
			Set<TValue> result;
			
			do {
				//logger.info("root " + this.lockManager.getLockId(this.root.getId()));
				Iterator<Map.Entry<Key, TValue>> iterator = this.getRange(comparison, key);
				result = new HashSet<>();

				while (iterator.hasNext()) {
					result.add(iterator.next().getValue());
				}
				visits.get().clear();
				Trace.clear();
			} while (!this.commit());

			this.updateStatistics(1, result.size());

			return result;
		} finally {
			this.lockManager.unlockAll();
		}
	
	}

	public Set<TValue> getAllBetween(Key firstKey, Key lastKey) throws Exception {
		if (firstKey.compareTo(lastKey) > 0) {
			return new HashSet<>();
		}
		
		try {
			this.lockManager.readLock(this.guardNode.getId());
			this.lockManager.readLock(this.root.getId());

			Set<TValue> result;
			Cursor first = this.getFirstGE(firstKey);
			Cursor last = this.getFirstLE(lastKey.with(Key.MAX));

			if (first.currentKey().compareTo(firstKey) < 0) {
				this.commit();
				return new HashSet<>();
			}

			do {
				Iterator<Map.Entry<Key, TValue>> iterator = new RangeIterator(first, last);
				result = new HashSet<>();

				while (iterator.hasNext()) {
					result.add(iterator.next().getValue());
				}
			} while (!this.commit());


			this.updateStatistics(1, result.size());

			return result;
		} finally {
			this.lockManager.unlockAll();
		}
	}

	public Set<Key> getAllKeys(Comparison comparison, Key key) throws Exception {
		try {
			Set<Key> result;
			do {
				logger.info("root " + this.lockManager.getLockId(this.root.getId()));
				Iterator<Map.Entry<Key, TValue>> iterator = this.getRange(comparison, key);
				result = new HashSet<>();

				while (iterator.hasNext()) {
					result.add(iterator.next().getKey());
				}
				visits.get().clear();
				Trace.clear();
			} while (!this.commit());

			this.updateStatistics(1, result.size());

			return result;
		} finally {
			this.lockManager.unlockAll();
		}
	}

	private TreeNode getChild(TreeNode node, int idx) throws Exception {
		Long nodeId = (Long) node.getValue(idx);

		if (nodeId == null || nodeId == 0) {
			throw new IllegalStateException("The node has a null or zero node id.");
		}

		return this.getNode(nodeId);
	}

	private TreeNode getChild(TreeNode node, Key key) throws Exception {
		return this.getChild(node, node.getChildIndex(key));
	}

	Cursor getFirst() throws Exception {
		TreeNode node = this.root;

		while (!node.isLeaf()) {
			node = this.getChild(node, 0);
		}

		return new Cursor(node, node.size() == 0 ? -1 : 0, true);
	}

	Cursor getFirstGE(Key key) throws Exception {
		logs.get().setLength(0);
		visits.get().clear();
		boolean trace = TRACE && false;
		Cursor cursor = this.getFirstLE(key);
		Key max = key.with(Key.MAX);

		if (!cursor.found) {
			cursor.next();
			if (trace)
				visits.get().add(new Visit("getFirstGE after not found", cursor.node));
		}

		if (cursor.currentKey() == null) {
			return cursor;
		}

		if (key.getKeys().length == cursor.currentKey().getKeys().length) {
			return cursor;
		}

		while (cursor.currentKey().compareTo(max) < 0 && !cursor.currentKey().startsWith(key) && cursor.next()) {
			if (trace)
				visits.get().add(new Visit("getFirstGE in loop", cursor.node));
		}

		return cursor;
	}

	Cursor getFirstLE(TreeNode node, Key key) {
		this.checkReadLock(node);

		int index = node.binarySearch(key);

		if (index >= 0) {
			return new Cursor(node, index, true);
		} else {
			return new Cursor(node, Math.abs(index) - 2, false);
		}
	}

	Cursor getFirstLE(Key key) throws Exception {
		TreeNode node = this.root;
		boolean trace = false;
		Cursor cursor;

		this.checkReadLock(node);

		if (trace)
			visits.get().add(new Visit("root in getFirstLE", node));

		for (;;) {
			cursor = this.getFirstLE(node, key);

			if (cursor.isLeaf()) {
				break;
			} else {
				node = this.getChild(cursor.node, Math.max(0, cursor.index));

				if (trace)
					visits.get().add(new Visit("descend to index " + Math.max(0, cursor.index), node));
				this.checkReadLock(node);
			}
		}

		return cursor;
	}

	Cursor getLast() throws Exception {
		TreeNode node = this.root;

		while (!node.isLeaf()) {
			node = this.getChild(node, node.size() - 1);
		}

		return new Cursor(node, -1, true);
	}

	Cursor getLastEQ(Key key) throws Exception {
		Cursor cursor = this.getFirstLE(key.with(Key.MAX));

		if (cursor.currentKey() == null) {
			cursor.next();
		}

		return cursor;
	}

	private TreeNode getNode(long id) throws Exception {

		this.lockManager.readLock(id);
		this.lockManager.checkReadLock(id);

		TreeNode node = this.treeStore.getNode(id);

		if (node.getId() != id) {
			throw new IllegalStateException("read wrong node want " + id + " got " + node.getId());
		}

		this.checkReadLock(node);

		return node;

	}

	@SuppressWarnings("unused")
	private void dumpVisited() {
		String indent = "";
		int level = 1;

		for (Visit visit : visits.get()) {
			Trace.log("");
			Trace.log(indent + visit.reason + " Level" + level + "id " + visit.node.getId() + " next = "
					+ visit.node.getNext());
			Trace.log("");

			for (int idx = 0; idx < visit.node.size(); idx++) {
				Trace.log(indent + idx + ". " + visit.node.getKey(idx));
			}

			level++;
			indent += "    ";
		}

		logger.info(Trace.asString());

		Trace.clear();
	}

	private Iterator<Map.Entry<Key, TValue>> getRange(Comparison comparison, Key key) throws Exception {

		visits.get().clear();
		Trace.clear();

		Iterator<Map.Entry<Key, TValue>> empty = new HashMap<Key, TValue>().entrySet().iterator();

		this.lockManager.readLock(this.guardNode.getId());
		this.lockManager.readLock(this.root.getId());
		
		this.lockManager.checkReadLock(this.guardNode.getId());
		this.lockManager.readLock(this.root.getId());

		Trace.clear();

		if (this.root.isEmpty()) {
			return empty;
		}

		Cursor first;
		Cursor last;

		Key wildcard = key.with(Key.WILD_CARD);
		Key max = key.with(Key.MAX);
		Map<Key, TValue> map = new HashMap<>();

		switch (comparison) {
		case EQ:
			first = this.getFirstGE(key);
			last = this.getLastEQ(key);

			/*
			 * boolean hasData = first.currentKey() != null &&
			 * first.currentKey().compareTo(key) >= 0 && first.currentKey().compareTo(max) <
			 * 0;
			 * 
			 * if (trace && !hasData) { Trace.log("missing key = " + key); dumpVisited(); }
			 */

			return first.currentKey() != null && first.currentKey().compareTo(key) >= 0
					&& first.currentKey().compareTo(max) < 0 ? new RangeIterator(first, last) : empty;

		case GE:
			first = this.getFirstGE(key);
			last = this.getLast();

			return first.currentKey().compareTo(key) >= 0 ? new RangeIterator(first, last) : empty;

		case GT:
			first = this.getFirstGE(max);
			last = this.getLast();

			return first.currentKey().compareTo(max) > 0 ? new RangeIterator(first, last) : empty;

		case LE:
			first = this.getFirst();
			last = this.getFirstLE(max);

			return last.currentKey() == null ? empty : new RangeIterator(first, last);

		case LT:
			first = this.getFirst();
			last = this.getFirstGE(key);

			while (!first.matches(last)) {
				if (first.currentKey() != null) {
					map.put(first.currentKey(), first.currentValue());
				}

				if (!first.next()) {
					break;
				}
			}

			if (first.currentKey().compareTo(key) < 0) {
				map.put(first.currentKey(), first.currentValue());
			}

			return map.entrySet().iterator();

		case NE:
			first = this.getFirst();

			do {
				if (first.currentKey().compareTo(wildcard) != 0) {
					map.put(first.currentKey(), first.currentValue());
				}
			} while (first.next());

			return map.entrySet().iterator();
		default:
			return empty;
		}

	}

	public long getReadCount() {
		return this.treeStore.getReadCount();
	}

	public long getWriteCount() {
		return this.treeStore.getWriteCount();
	}

	public int getSelectivity() {
		long searches = this.getCount.get();
		long results = this.resultCount.get();

		return searches == 0 ? 1 : (int) (results / searches);
	}

	public String getStats() {
		return String.format("Gets: %10d Puts: %10d Removes: %10d %s", this.getCount.get(), this.putCount.get(),
				this.removeCount.get(), this.treeStore.getStats());

	}

	@Override
	public Iterator<Entry<Key, TValue>> iterator() {
		try {
			return this.root.size() == 0 ? new EmptyIterator() : new RangeIterator(this.getFirst(), this.getLast());
		} catch (Exception exc) {
			throw new IllegalStateException(exc);
		} finally {
			this.lockManager.unlockAll();
		}
	
	}

	@SuppressWarnings("unchecked")
	public void put(Key key, TValue value) throws Exception {

		this.putCount.incrementAndGet();

		Trace.clear();
		visits.get().clear();

		if (value instanceof Blob blob && !blob.isOnDisk() && blob.getSize() > this.nodeSize / 2) {
			value = (TValue) blob.toDisk(this.treeStore);
		}

		for (;;)
			try {
				this.treeStore.clear();
				this.putInternal(key, value);
				return;
			} catch (UpgradeException exc) {

			} catch (Exception exc) {
				this.treeStore.rollback();
				throw exc;
			} finally {
				this.commit();
			}
	}

	private void putInternal(Key key, TValue value) throws Exception {
		long stamp = this.lockManager.writeLock(this.guardNode.getId());

		TreeNode newNode = this.putInternal(this.root, key, value, 0, this.guardNode, stamp);

		if (newNode != null) {
			this.lockManager.checkWriteLock(this.guardNode.getId());
			this.lockManager.checkWriteLock(this.root.getId());
			
			TreeNode newRoot = this.createNode(false);

			this.lockManager.writeLock(newRoot.getId());

			newRoot.addEntry(this.root.firstKey(), this.root.getId());
			newRoot.addEntry(newNode.firstKey(), newNode.getId());

			if (root.getNext() != newNode.getId()) {
				newNode.setNext(this.root.getNext());
				this.root.setNext(newNode.getId());
			}

			this.root = newRoot;
			this.treeStore.saveNode(newRoot);
			this.treeStore.saveRoot(newRoot.getId());
		}

	}

	private TreeNode putInternal(TreeNode node, Key key, TValue value, int level, TreeNode parent, long parentStamp) throws Exception {
		long stamp = this.lockManager.writeLock(node.getId());

		this.checkWriteLock(node);
		
		logs.get().setLength(0);
		
		int newSize = node.storageSize() + TreeNode.getEntrySize(key, value);
		
		if (parent == this.guardNode && newSize <= this.nodeSize) {
			this.lockManager.unlock(parent.getId(), parentStamp);
		} else if (newSize < this.nodeSize && parent != null && parent.getChildIndex(key) != 0) {
			this.lockManager.unlock(parent.getId(), parentStamp);
		} 
		
		if (node.isLeaf()) {
			this.checkWriteLock(node);

			Cursor cursor = this.getFirstLE(node, key);

			if (cursor.found) {

				if (cursor.currentValue() instanceof Blob blob && blob.isOnDisk()) {
					blob.releaseDiskSpace(this.treeStore);
				}
				
				int before = node.storageSize();
				node.setValue(node.getChildIndex(key), value);
				
				logs.get().append("set child from " + before + " to" + node.storageSize() + "\n");

				if (node.storageSize() < this.nodeSize) {
					this.saveNode(node);
				}
			} else {
				this.lockManager.checkWriteLock(node.getId());
				int before = node.storageSize();
				
				this.lockManager.checkWriteLock(node.getId());
				node.addEntry(node.getInsertionPoint(key), key, value);
				
				int after = node.storageSize();
				int elementSize = TreeNode.getEntrySize(key, value);
				int total = before + elementSize;
				
				logs.get().append("added child from " + before + " to" + after + " element " + elementSize + " total" + total + "\n");

				if (node.storageSize() < this.nodeSize) {
					this.saveNode(node);
				}

				this.size.incrementAndGet();
			}
		} else {
			this.checkWriteLock(node);

			TreeNode oldNode = this.getChild(node, key);
			TreeNode newNode = putInternal(oldNode, key, value, level + 1, node, stamp);

			if (newNode != null) {
				Key newKey = newNode.firstKey();
				this.lockManager.writeLock(newNode.getId());

				node.addEntry(node.getInsertionPoint(newKey), newKey, newNode.getId());

				this.saveNode(node);
			} else if (node.getChildIndex(key) == 0 && !node.getKey(0).equals(oldNode.firstKey())) {
				node.setKey(0, oldNode.firstKey());
				this.saveNode(node);
			}
		}

		if (node.storageSize() > this.nodeSize) {
			this.checkWriteLock(node);
			this.checkWriteLock(parent);
			TreeNode newNode = node.split(this.treeStore.allocateNode());

			this.saveNode(newNode);
			this.saveNode(node);

			return newNode;
		}

		return null;

	}

	public void remove(Key key) throws Exception {
		this.removeCount.incrementAndGet();

		Trace.clear();
		visits.get().clear();

		for (;;) {
			try {
				this.treeStore.clear();
				this.removeInternal(key);
				return;
			} catch (UpgradeException exc) {

			} catch (Exception exc) {
				this.treeStore.rollback();
				throw exc;
			} finally {
				this.commit();
			}
		}
	}

	private void removeInternal(Key key) throws Exception {
		long stamp = this.lockManager.writeLock(this.guardNode.getId());

		this.removeInternal(this.root, key, 0, this.guardNode, stamp, true);

		if (this.root.size() == 1 && !this.root.isLeaf()) {
			this.root = this.getChild(root, 0);
			this.treeStore.saveRoot(this.root.getId());
		}

	}

	private void removeInternal(TreeNode node, Key key, int level, TreeNode parent, long parentStamp,
			boolean canUnlockParent) throws Exception {

		long stamp = this.lockManager.writeLock(node.getId());

		if (node.storageSize() > this.nodeSize / 2 && parent != null && canUnlockParent && node.size() != 1) {
			this.lockManager.unlock(parent.getId(), parentStamp);
		}

		if (node.isLeaf()) {
			Cursor cursor = this.getFirstLE(node, key);

			if (cursor.found) {
				this.lockManager.checkWriteLock(node.getId());

				if (cursor.currentValue() instanceof Blob blob && blob.isOnDisk()) {
					blob.releaseDiskSpace(this.treeStore);
				}

				node.remove(cursor.index);
				this.saveNode(node);
				this.size.decrementAndGet();
			}

			this.lockManager.checkWriteLock(node.getId());
		} else {
			this.lockManager.checkWriteLock(node.getId());

			int index = node.getChildIndex(key);

			TreeNode child = this.getChild(node, index);

			this.lockManager.checkReadLock(child.getId());

			// Remove the node from the child. If we removed the first child of the
			// child then update node

			this.removeInternal(child, key, level + 1, node, stamp, index != 0);

			if (index == 0 && !child.isEmpty()) {
				this.lockManager.checkLock(child.getId());
				this.lockManager.checkWriteLock(node.getId());

				node.setKey(0, child.firstKey());
				this.saveNode(node);
			}

			// If node has two or more children and child is the not last node
			// then try to merge child with the next node.

			int childSize = child.storageSize();

			if (childSize <= this.nodeSize / 2 && node.size() > 1 && index < node.size() - 1
					&& lockManager.isWriteLocked(node.getId())) {
				TreeNode nextChild = this.getChild(node, index + 1);

				this.lockManager.checkReadLock(nextChild.getId());
				this.lockManager.writeLock(nextChild.getId());

				if (canMerge(child, nextChild)) {
					child.merge(nextChild);
					node.remove(index + 1);

					this.lockManager.checkWriteLock(node.getId());

					this.saveNode(node);
					this.saveNode(child);
					this.treeStore.releaseNode(nextChild.getId());
				}
			}
		}

	}

	private void saveNode(TreeNode node) throws Exception {
		this.treeStore.saveNode(node);
	}

	private void updateStatistics(long searches, long results) {
		this.getCount.addAndGet(searches);
		this.resultCount.addAndGet(results);

		if (System.currentTimeMillis() > this.lastStatsTime + 60 * 60 * 1000) {
			try {
				this.treeStore.setStatistics(this.getCount.get(), this.resultCount.get());
			} catch (Exception exc) {
				logger.info("Exception saving statistics " + exc.getMessage());
			}
		}
	}

	public long size() {
		return size.get();
	}

	class Cursor {
		private final boolean found;
		private int index;
		private TreeNode node;

		Cursor(TreeNode node, int index, boolean found) {
			this.node = node;
			this.index = index;
			this.found = found;
		}

		Key currentKey() {
			return this.node.getKey(this.index);
		}

		@SuppressWarnings("unchecked")
		TValue currentValue() {
			return (TValue) this.node.getValue(Math.max(0, this.index));
		}

		boolean hasNext() {
			return this.index < this.node.size() - 1 || this.node.getNext() != -1;
		}

		boolean isLeaf() {
			return this.node.isLeaf();
		}

		boolean matches(Cursor cursor) {
			return this.node.getId() == cursor.node.getId() && this.index == cursor.index;
		}

		boolean next() throws Exception {
			if (this.index < this.node.size() - 1) {
				this.index++;
				return true;
			} else if (this.node.getNext() == -1) {
				return false;
			} else {
				do {
					this.node = Tree.this.getNode(this.node.getNext());
				} while (this.node.isEmpty() && this.node.getNext() != -1);

				this.index = 0;
				return true;
			}
		}

		@Override
		public String toString() {
			return "Cursor [found=" + found + ", index=" + index + ", node=" + node + "]";
		}

	}

	class TreeIterator implements Iterator<Map.Entry<Key, TValue>> {
		private Cursor cursor;
		private boolean hasNext;

		TreeIterator() {
			try {
				this.cursor = Tree.this.getFirst();
				this.hasNext = this.cursor.found;
			} catch (Exception exc) {
				throw new IllegalStateException(exc);
			}
		}

		@Override
		public boolean hasNext() {
			return this.hasNext;
		}

		@Override
		public Entry<Key, TValue> next() {
			if (!this.hasNext) {
				return null;

			}

			try {
				Entry<Key, TValue> result = new SimpleEntry<>(this.cursor.currentKey(), this.cursor.currentValue());
				this.hasNext = this.cursor.next();

				return result;
			} catch (Exception exc) {
				throw new IllegalStateException(exc);
			}
		}

	}

	class EmptyIterator implements Iterator<Map.Entry<Key, TValue>> {

		@Override
		public boolean hasNext() {
			return false;
		}

		@Override
		public Entry<Key, TValue> next() {
			return null;
		}
	}

	class RangeIterator implements Iterator<Map.Entry<Key, TValue>> {
		private final Cursor first;
		private final Cursor last;
		private boolean hasNext = true;

		RangeIterator(Cursor first, Cursor last) {
			this.first = first;
			this.last = last;
		}

		@Override
		public boolean hasNext() {
			return this.hasNext;
		}

		@SuppressWarnings("unchecked")
		@Override
		public Entry<Key, TValue> next() {
			try {
				Key key = this.first.currentKey();
				TValue value = this.first.currentValue();

				if (value instanceof Blob blob && blob.isOnDisk()) {
					value = (TValue) blob.fromDisk(treeStore);
				}

				Entry<Key, TValue> result = new SimpleEntry<>(key, value);

				this.hasNext = !first.matches(last) && first.next();
				return result;
			} catch (Exception exc) {
				throw new IllegalStateException(exc);
			} finally {
				lockManager.unlockAll();
			}

		}
	}

}
