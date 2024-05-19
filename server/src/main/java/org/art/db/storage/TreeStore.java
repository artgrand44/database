package org.art.db.storage;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

import org.art.db.common.Blob;
import org.art.db.common.BlobStore;
import org.art.db.common.GUID;
import org.art.db.common.Key;
import org.art.db.common.Scheduler;
import org.art.db.common.TypedInputStream;
import org.art.db.common.TypedOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.RemovalCause;
import java.util.AbstractMap.SimpleEntry;

public class TreeStore implements Closeable, BlobStore {
	
	private static final Logger logger = LoggerFactory.getLogger(TreeStore.class);
	
	private static final ObjectMapper MAPPER = new ObjectMapper();
	
	private static final NodeCache cache = new NodeCache();
	
	enum Operation {
		Release,
		Update,
		SetRoot,
		EOF
	}
	
	record LogKey(Operation op, long id) {
		
	}
	
	record Update(Object data, boolean transaction) {
		
	}
	private final ReentrantLock lock = new ReentrantLock();
	private final ReentrantLock flushLock = new ReentrantLock();
	private final BlockFile file;
	private final File logFile;
	private final int blockSize;
	
	private final File rawFile;
	private final AtomicLong getCount = new AtomicLong();
	private final AtomicLong hitCount = new AtomicLong();
	private final AtomicLong updateCount = new AtomicLong();
	private final AtomicLong writeCount = new AtomicLong();
	private List<Long> freeBlocks = new ArrayList<>();
	private Header header;
	private volatile boolean isClosed;
	
	
	private static final TreeNode NULL_NODE = new TreeNode();
	
	
	private volatile ConcurrentMap<LogKey, Update> currentDelta = new ConcurrentHashMap<>();	
	private volatile ConcurrentMap<LogKey, Update> prevDelta;
	
	
	private final static ThreadLocal<Map<LogKey, Object>> threadDeltas = ThreadLocal.withInitial(() -> new HashMap<>());
	
	private final static LogKey SET_ROOT_KEY = new LogKey(Operation.SetRoot, 0);
	
	private final static LogKey EOF_ROOT_KEY = new LogKey(Operation.EOF, 0);
	
	private final static Map.Entry<LogKey, Update> EOF = new SimpleEntry<>(EOF_ROOT_KEY, new Update(null, false));
	
	public TreeStore() throws IOException, Exception {
		this(File.createTempFile("tree", ".dat"));
	}
		
	public TreeStore(File file) throws Exception { 
		this(file, 2048);
	}
	
	
	public TreeStore(File file, int blockSize) throws Exception {
		
		this.blockSize = blockSize;
		this.file = new BlockFile(file, blockSize);
		this.logFile = new File(Files.getLogRoot(), file.getParentFile().getName() + "/" + file.getName().replace('.', '_') + ".log");
		this.rawFile = file;
		this.logFile.getParentFile().mkdirs();
				
		if (this.file.isNew()) {
			this.header = new Header();
			this.file.setLength(this.blockSize);
			this.writeHeader();
		} else {
			this.header = new Header(this.file.readBlockAsStream(0));
			
			if (!this.header.flushed) {
				this.header.freeBlock = -1;				
			}
			
			this.header.flushed = false;
			this.writeHeader();
			
			if (this.logFile.exists()) {
				this.recover();
			}
		}			
		
	}

	public void close() throws IOException {
		this.lock.lock();
		
		try {
			if (!this.isClosed) {
				this.flushAll();			
				this.currentDelta.clear();

				this.isClosed = true;
				this.file.close();
				this.logFile.delete();		
			}
		} catch (Exception exc) {
			logger.info(exc.getMessage());
		} finally {
			this.lock.unlock();
		}

	}

	public void clear() {
		threadDeltas.get().clear();
	}
	
	public void commit() throws Exception {
		
		Map<LogKey, Object> deltas = threadDeltas.get();
		
		if (deltas.isEmpty()) {
			return;
		}
		
		this.lock.lock();

		this.updateCount.addAndGet(threadDeltas.get().size());

		boolean doFlush = false;
		
		try {
			for (Map.Entry<LogKey, Object> entry : deltas.entrySet()) {
				LogKey key = entry.getKey();
				boolean transactional = deltas.size() > 1 || (this.currentDelta.containsKey(key) && this.currentDelta.get(key).transaction);
				
				this.currentDelta.put(key, new Update(entry.getValue(), transactional));
			}
			
			threadDeltas.get().clear();

			// If the batch is full or the batch contains a change to header.root
			// then flush it.  Header.root changes need to be flushed immediately
			// since the changes within a batch aren't ordered.  If we create a batch
			// with two changes to the route, they might be applied in the wrong order.
			
			if (this.prevDelta == null && this.currentDelta.size() > 200) {
				this.prevDelta = this.currentDelta;
				this.currentDelta = new ConcurrentHashMap<>();
				doFlush = true;
			} 
		} finally {
			this.lock.unlock();
		}

		if (doFlush) {
			Scheduler.DEFAULT.submit(() -> this.flushPrevious());
		}
	}
	
	public void crash() throws IOException {
		this.file.close();
	}
	
	public long allocateNode() throws Exception {
		this.lock.lock();
		
		try {
			if (this.freeBlocks.isEmpty() && this.header.freeBlock == -1) {
				this.freeBlocks = this.file.addBlocks(10);
			} else if (this.freeBlocks.isEmpty()) {
				FreeBlock block = new FreeBlock(this.file.readBlockAsStream(header.freeBlock));

				this.freeBlocks = block.blocks;
				this.header.freeBlock = block.next;

				this.writeHeader();
			}

			return this.freeBlocks.remove(0);
		} finally {
			this.lock.unlock();
		}
	}

	public TreeNode createNode(boolean isLeaf) throws Exception {
		return new TreeNode(isLeaf, this.allocateNode());
		
	}

	private void flush(ConcurrentMap<LogKey, Update> delta)  {
		this.flushLock.lock();
		
		try {
			List<Map.Entry<LogKey, Update>> entries = new LinkedList<>();
			
			for (Map.Entry<LogKey, Update> entry : delta.entrySet()) {
				LogKey key = entry.getKey();
				Update update = entry.getValue();
				Object value = update.data;
				
				entries.add(new SimpleEntry<>(key, new Update(value instanceof TreeNode node ? node.serialize() : value, update.transaction)));
			}
			
			entries.add(EOF);
			
			// Write the log entries then close the log.  This is the fastest way 
			// to guarantee that the end of file marker has been written to the
			// operating system.
				
			try (DataOutputStream log = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(this.logFile)))){

				if (this.isClosed) {
					return;
				}

				this.writeCount.addAndGet(delta.size());

				log.writeInt(entries.size());

				for (Map.Entry<LogKey, Update> entry : entries) {
					if (entry.getValue().transaction) {
						flush(log, entry);
					}
				} 

				log.flush();
			}	
						
			for (Map.Entry<LogKey, Update> entry : entries) {
				LogKey key = entry.getKey();
				Object data = entry.getValue().data;
				
				switch (key.op) {
				case EOF:
					break;
				case Release:
					this.freeNode(key.id);
					break;
				case SetRoot:
					this.header.root = key.id;
					this.writeHeader();
					break;
				case Update:
					this.file.writeBlock((byte[])data, key.id);
					break;
				}
			}
			
			this.logFile.delete();
		} catch (Exception exc) {
			Metrics.getInstance().logAlert("There was an error flushing the database");
			exc.printStackTrace();
			logger.info("Flush failed " + exc.getMessage());
		} finally {
			this.flushLock.unlock();
		}
	}
	
	private void flush(DataOutputStream log, Map.Entry<LogKey, Update> entry) throws Exception {
		LogKey key = entry.getKey();

		log.writeLong(key.id);
		log.writeUTF(key.op.toString());

		if (key.op == Operation.Update) {
			byte[] bytes = (byte[])entry.getValue().data;
			log.writeInt(bytes.length);
			log.write(bytes);
		} else if (key == SET_ROOT_KEY) {
			log.writeLong((Long)entry.getValue().data);
		}
	}
	public void flushAll() {
		this.lock.lock();
		
		try {
			this.flushPrevious();
			this.flush(this.currentDelta);
			
			this.header.flushed = true;
			this.writeHeader();
		} catch (Exception exc) {
			logger.info("Error flushing header " + exc.getMessage());
		} finally {
			this.lock.unlock();
		}
	}
	
	private void flushPrevious() {
		this.lock.lock();
		
		try {
			if (this.prevDelta != null && !this.isClosed) {
				this.flush(this.prevDelta);
				this.prevDelta = null;
			} 
		} finally {
			this.lock.unlock();
		}
	}
	
	@Override
	public Blob fromDisk(long firstBlock, int size) throws Exception {
		int offset = 0;
		int blockSize = this.getNodeSize();
		byte[] bytes = new byte[size];
		long blockId = firstBlock;
		
		while (blockId != -1) {
			byte[] block = this.getBlock(blockId);
			long nextBlockId;
			int bytesToRead = Math.min(blockSize -8, size - offset);
			
			try (TypedInputStream stream = new TypedInputStream(block)) {
				nextBlockId = stream.readLong();
				
				stream.read(bytes, offset, bytesToRead);
			}
			
			blockId = nextBlockId;
			offset += bytesToRead;
		}
		
		return new Blob(bytes);
	}

	
	private void recover() {
		try (FileInputStream fstream = new FileInputStream(this.logFile); DataInputStream stream = new DataInputStream(fstream)) {
			int count = stream.readInt();

			List<Map.Entry<LogKey, Object>> items = new LinkedList<>();

			for (int idx = 0; idx < count; idx++) {
				long id = stream.readLong();
				Operation op = Enum.valueOf(Operation.class, stream.readUTF());
				Object value = null;

				switch (op) {
				case Update:
					byte[] bytes = new byte[stream.readInt()];
					stream.read(bytes);
					value = bytes;
					break;
				case EOF:
					break;
				case SetRoot:
					value = stream.readLong();
				default:
					break;
				}

				items.add(new SimpleEntry<>(new LogKey(op, id), value));							
			}

			if (items.isEmpty() || items.getLast().getKey().op != Operation.EOF) { 
				return;
			}
			
			for (Map.Entry<LogKey, Object> item : items) {
				LogKey key = item.getKey();

				switch (key.op) {
				case Release:
					break;
				case SetRoot:
					this.header.root = (Long)item.getValue();
					this.writeHeader();
					break;
				case Update:
					this.file.writeBlock((byte[])item.getValue(), key.id);
					break;
				case EOF:
					break;
				}
			}

		} catch (Exception exc) {
			Metrics.getInstance().logAlert("There was an error recovering database");
			logger.info("Recovery failed " + exc.getMessage());
		}
	}

	public void releaseNode(long id) throws IOException {
		
		threadDeltas.get().put(new LogKey(Operation.Release, id), NULL_NODE);
	}
	
	
	public void rollback() {
		for (Map.Entry<LogKey, Object> entry : threadDeltas.get().entrySet()) {
			cache.invalidate(new NodeId(this, entry.getKey().id));
		}
		
		threadDeltas.get().clear();
	}

	public void freeNode(TreeNode node) throws Exception {
		this.freeNode(node.getId());
	}
	

	private void freeNode(long id) {
		this.lock.lock();
		
		try {
			if (this.freeBlocks.size() >= FreeBlock.MAX_BLOCKS) {
				List<Long> free = new ArrayList<>();

				for (int idx = 0; idx < FreeBlock.MAX_BLOCKS; idx++) {
					free.add(this.freeBlocks.remove(0));

				}

				FreeBlock block = new FreeBlock(id, this.header.freeBlock, free);

				this.header.freeBlock = id;

				this.writeFreeBlock(block);
				this.writeHeader();			
			} else {
				this.freeBlocks.add(id);
			}
		} catch (Exception exc) {
			logger.info("Exception freeing node " + exc.getMessage());
		} finally {
			this.lock.unlock();
		}

	}
	
	public byte[] getBlock(long block) throws IOException {
		NodeId nodeId = new NodeId(this, block);
		Object obj = cache.getIfPresent(nodeId);
		
		if (obj instanceof byte[] bytes) {
			return bytes;
		}
				
		byte[] bytes = this.file.readBlock(block);
		
		cache.put(nodeId, bytes);
		
		return bytes;
	}
	
	public BitSet getBloomFilter() throws IOException {
		if (this.header.bloomOffset == -1 || this.header.bloomSize == 0) {
			throw new IllegalStateException("The bloom filter is missing");
		}
		
		return BitSet.valueOf(this.file.getBytes(this.header.bloomOffset, this.header.bloomSize));
	}
	
	public long getCreateTime() {
		return this.header.createTime;
	}
	
	
	public GUID getHighGUID()throws IOException {
		GUID guid = new GUID();
		
		guid.setValue(this.header.highGUID);
		
		return guid;
	}
	
	public int getMergeLevel() {
		return this.header.mergeLevel;
	}
	
	public Metadata getMetadata() throws IOException {
		if (this.header.metadataOffset == -1 || this.header.metadataSize == 0) {
			throw new IllegalStateException("The metadata is missing");
		}
		
		return MAPPER.readValue(this.file.getBytes(this.header.metadataOffset, this.header.metadataSize), Metadata.class);
	}
	
	public TreeNode getNode(long id) throws Exception {
		NodeId nodeId = new NodeId(this, id);
		Object obj = cache.getIfPresent(nodeId);

		this.getCount.incrementAndGet();
		
		if (obj instanceof TreeNode node) {
			this.hitCount.incrementAndGet();
			return node;
		} else if (obj instanceof byte[] bytes) {
			TreeNode node = new TreeNode(bytes);
			
			this.hitCount.incrementAndGet();
			return node;
		} else {
			LogKey key = new LogKey(Operation.Update, id);
			
			if (this.currentDelta.containsKey(key)) {
				return (TreeNode)this.currentDelta.get(key).data;
			} else if (prevDelta != null && this.prevDelta.containsKey(key)) {
				return (TreeNode)this.prevDelta.get(key).data;
			} else {
				byte[] bytes = this.file.readBlock(key.id);
				TreeNode node = new TreeNode(bytes);

				cache.put(nodeId, node);

				return node;
			}
		}
			
	}

	public int getNodeSize() {
		return this.blockSize;
	}
	
	public byte[] getZombies() throws IOException {
		if (this.header.zombieOffset == -1 || this.header.zombieSize == 0) {
			throw new IllegalStateException("The zombies are missing");
		}
		
		return this.file.getBytes(this.header.zombieOffset, this.header.zombieSize);
	}
	
	public boolean isNew() {
		return this.file.isNew();
	}
	
	public void saveRoot(long id) throws IOException {
		
		threadDeltas.get().put(SET_ROOT_KEY, id);
	}
	
	
	public void saveNode(TreeNode node) throws Exception {
		NodeId id = new NodeId(this, node.getId());
		
		cache.put(id, node);	
		threadDeltas.get().put(new LogKey(Operation.Update, node.getId()), new TreeNode(node));
		
	}

	public long getReadCount() {
		return this.file.getReadCount();
	}
	
	public String getStats() {
		return (String.format("Gets: %10d Hits: %10d Updates: %10d Writes: %10d",
			    this.getCount.get(),
			    this.hitCount.get(),
			    this.updateCount.get(),
			    this.writeCount.get()));
	
	
	}
	
	public long getWriteCount() {
		return this.file.getWriteCount();
	}
	
	public long getRoot() {
		return this.header.root;
	}
	
	public long getSearchCount() {
		return this.header.searchCount;
	}
	
	public long getResultCount() {
		return this.header.resultCount;
	}
	
	
	@Override
	public void releaseBlob(long firstBlock) throws Exception {
		long block = firstBlock;
		
		while (block != 1) {
			byte[] bytes = this.getBlock(block);
			
			try (TypedInputStream stream = new TypedInputStream(bytes)) {
				long nextBlockId = stream.readLong();
				
				this.releaseNode(block);
				block = nextBlockId;
			}
			
		}
		
	}
	
	public void setBloomFilter(BitSet set) throws Exception {
		byte[] bytes = set.toByteArray();
		this.header.bloomOffset = this.file.append(bytes);
		this.header.bloomSize = bytes.length;
		
		this.writeHeader();
				
	}
	
	public void setCreateTime(long createTime) throws Exception {
		this.header.createTime = createTime;
		
		this.writeHeader();
	}
	
	public void setHighGUID(GUID guid) throws Exception {
		try (TypedOutputStream stream = new TypedOutputStream()) {
			this.header.highGUID = guid.getValue();
			this.writeHeader();
		}
	}
	
	public void setMergeLevel(int mergeLevel) throws Exception {
		this.header.mergeLevel = mergeLevel;
		
		this.writeHeader();
	}
	
	public void setMetadata(Metadata metadata) throws Exception {
		byte[] bytes = MAPPER.writeValueAsBytes(metadata);
		this.header.metadataOffset = this.file.append(bytes);
		this.header.metadataSize = bytes.length;
		
		this.writeHeader();		
		
	}
	
	public void setZombies(Set<Key> keys) throws Exception {
		byte[] bytes;
		
		try (TypedOutputStream stream = new TypedOutputStream()) {
			stream.writeInt(keys.size());
			
			for (Key key : keys) {
				key.serialize(stream);
			}
			
			bytes = stream.toByteArray();
		}
		
		this.header.zombieOffset = this.file.append(bytes);
		this.header.zombieSize = bytes.length;
		
		this.writeHeader();
	}
	
	public void setStatistics(long searchCount, long resultCount) throws Exception {
		this.header.searchCount = searchCount;
		this.header.resultCount = resultCount;
		
		this.writeHeader();
	}
	

	@Override
	public Blob toDisk(byte[] bytes) throws Exception {
		int blockSize = this.getNodeSize();
		long blockId = this.allocateNode();
		long firstBlock = blockId;
		int offset = 0;
		
		while (offset < bytes.length) {
			int remainder = bytes.length - offset;
			long nextBlockId = remainder > blockSize - 8 ? this.allocateNode() : -1;
			int bytesToWrite = Math.min(blockSize - 8, bytes.length - offset);
			
			try (TypedOutputStream stream = new TypedOutputStream()) {
				stream.writeLong(nextBlockId);
				
				stream.write(bytes, offset, bytesToWrite);
				
				this.writeBlock(stream.toByteArray(), blockId);
			}
			
			blockId = nextBlockId;
			offset += bytesToWrite;
			
		}
		
		return new Blob(firstBlock, bytes.length);
	}

	@Override
	public String toString() {
		return "TreeStore [" + rawFile.getName() + "]";
	}

	public void writeBlock(byte[] bytes, long block) throws IOException {
		NodeId nodeId = new NodeId(this, block);
		
		cache.put(nodeId, bytes);
		this.file.writeBlock(bytes, block);
	}
	
	private void writeFreeBlock(FreeBlock block) throws Exception {
		this.file.writeBlock(block.serialize(), block.self);
	}
	
	private void writeHeader() throws IOException {
		this.file.writeBlock(this.header.serialize(), 0);
	}
	
	static class FreeBlock {
		private long self;
		private long next;
		private List<Long> blocks = new ArrayList<>();
		
		static int MAX_BLOCKS = 253;
		
		FreeBlock(long self, long next, List<Long> blocks) {
			this.self = self;
			this.next = next;
			this.blocks = blocks;
		}
		
		FreeBlock(TypedInputStream stream) throws Exception {
			int count = stream.readInt();
			
			this.self = stream.readLong();
			this.next = stream.readLong();
			
			for (int idx = 0; idx < count; idx++) {
				this.blocks.add(stream.readLong());
			}
		}
		
		byte[] serialize() throws Exception {
			
			try ( TypedOutputStream stream = new TypedOutputStream()) {
				stream.writeInt(this.blocks.size());
				stream.writeLong(this.self);
				stream.writeLong(this.next);
				
				for (long block : this.blocks) {
					stream.writeLong(block);
				}
				
				return stream.toByteArray();
			}
			
		}

		@Override
		public String toString() {
			return "FreeBlock [self=" + self + ", next=" + next + ", blocks=" + blocks.size() + "]";
		}
		
		
		
	}
	
	static class Header {
		String magic = "MAGIC";
		long root = -1;
		long freeBlock = -1;
		long bloomOffset = -1;
		int bloomSize = 0;
		long highGUID = -1;
		long metadataOffset = -1;
		int metadataSize = 0;
		long zombieOffset = -1;
		int zombieSize = 0;
		long searchCount;
		long resultCount;
		boolean flushed = false;
		long createTime = 0;
		int mergeLevel = 0;
		
		Header() {
		}
		
		Header(TypedInputStream stream) throws Exception {
			this.magic = stream.readUTF();
			this.root = stream.readLong();
			this.freeBlock = stream.readLong();
			this.bloomOffset = stream.readLong();
			this.bloomSize = stream.readInt();
			this.highGUID = stream.readLong();
			this.metadataOffset = stream.readLong();
			this.metadataSize = stream.readInt();
			this.zombieOffset = stream.readLong();
			this.zombieSize = stream.readInt();
			this.searchCount = stream.readLong();
			this.resultCount = stream.readLong();
			this.flushed = stream.readBoolean();
			this.createTime = stream.readLong();
			this.mergeLevel = stream.readInt();
			
			if (!this.magic.equals("MAGIC")) {
				throw new Exception("Bad magic number");
			}			
		}
		
		byte[] serialize() throws IOException {
			try (TypedOutputStream stream = new TypedOutputStream()) {
				stream.writeUTF(this.magic);
				stream.writeLong(this.root);
				stream.writeLong(this.freeBlock);
				stream.writeLong(this.bloomOffset);
				stream.writeInt(this.bloomSize);
				stream.writeLong(this.highGUID);
				stream.writeLong(this.metadataOffset);
				stream.writeInt(this.metadataSize);
				stream.writeLong(zombieOffset);
				stream.writeInt(this.zombieSize);
				stream.writeLong(this.searchCount);
				stream.writeLong(this.resultCount);
				stream.writeBoolean(this.flushed);
				stream.writeLong(this.createTime);
				stream.writeInt(this.mergeLevel);
				
				return stream.toByteArray();
			}			
		}

		@Override
		public String toString() {
			return "Header [root=" + root + ", freeBlock=" + freeBlock + ", bloomOffset=" + bloomOffset
					+ ", zombieOffset=" + zombieOffset + ", flushed=" + flushed + "]";
		}

		
	}
	
	static record NodeId(TreeStore store, long id) {
		
	}
	
	static class NodeCache extends ConfigurableCache<NodeId, Object> {
		
		NodeCache() {
			super("org.art.db.nodeCache");
		}
		
		@Override
		public int weigh(NodeId id, Object obj) {
			return 1;
		}
		
		@Override
		public void onRemoval(NodeId key, Object node, RemovalCause cause) {
		}
	}

}
