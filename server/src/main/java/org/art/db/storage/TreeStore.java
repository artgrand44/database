package org.art.db.storage;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

import org.art.db.common.Blob;
import org.art.db.common.BlobStore;
import org.art.db.common.GUID;
import org.art.db.common.Scheduler;
import org.art.db.common.TypedInputStream;
import org.art.db.common.TypedOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.RemovalCause;

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
	
	static record LogKey(Operation op, long id) {
		
	}	
	
	static record LogValue(Object value, boolean transactional) {
		
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
	
	
	private volatile HashMap<LogKey, LogValue> currentBatch = new HashMap<>();	
	private volatile HashMap<LogKey, LogValue> prevBatch;
	
	private final static ThreadLocal<Map<LogKey, LogValue>> threadChanges = ThreadLocal.withInitial(() -> new HashMap<>());
	
	private final static LogKey ROOT_KEY = new LogKey(Operation.SetRoot, -1);
	
	public TreeStore(File file) throws Exception { 
		this(file, 2048);
	}
	
	
	public TreeStore(File file, int blockSize) throws Exception {
		
		this.blockSize = blockSize;
		this.file = new BlockFile(file, blockSize);
		this.logFile = new File(file + ".log");
		
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
				this.currentBatch.clear();

				this.isClosed = true;
				this.file.close();
				this.logFile.delete();		
			}
		} catch (Exception exc) {
			logger.error("Error closing segment " + exc.getMessage());
		} finally {
			this.lock.unlock();
		}

	}

	public void clear() {
		threadChanges.get().clear();
	}
	
	public void commit() throws Exception {
		
		Map<LogKey, LogValue> changes = threadChanges.get();
		
		if (changes.isEmpty()) {
			return;
		}
		
		this.lock.lock();

		boolean doFlush = false;
		
		this.updateCount.addAndGet(changes.size());
		
		try {
			
			// Merge the changes for this thread into the current batch
			
			for (Map.Entry<LogKey, LogValue> change : changes.entrySet()) {
				LogKey key = change.getKey();
				
				this.currentBatch.compute(key, (_, y) -> y == null 
						                                 ? new LogValue(change.getValue().value, changes.size() > 1) 
						                                 : new LogValue(change.getValue().value, changes.size() > 1 || y.transactional));
			}
					 
			threadChanges.get().clear();

			// If the batch is full then flush it.
			
			if (this.prevBatch == null && this.currentBatch.size() > 200) {
				this.prevBatch = this.currentBatch;
				this.currentBatch = new HashMap<>();
				
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

	private void flush(HashMap<LogKey, LogValue> batch)  {
		this.flushLock.lock();
		
		try {
			if (this.isClosed) {
				return;
			}

			// Write the log entries then close the log.  This is the fastest way 
			// to guarantee that the end of file marker has been written to the
			// operating system.
			
			int transactionCount = 0;
			
			for (LogValue value : batch.values()) {
				if (value.transactional) {
					transactionCount++;
				}
			}
			
			if (transactionCount != 0) {
				try (DataOutputStream log = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(this.logFile)))){
					this.writeCount.addAndGet(batch.size());

					log.writeInt(transactionCount);

					for (Map.Entry<LogKey, LogValue> entry : batch.entrySet()) {
						LogKey key = entry.getKey();
						LogValue value = entry.getValue();

						if (value.transactional) {
							log.writeLong(key.id);
							log.writeUTF(key.op.toString());

							if (key.op == Operation.Update) {
								byte[] bytes = ((TreeNode)value.value).serialize();
								log.writeInt(bytes.length);
								log.write(bytes);
							} else if (key.op == Operation.SetRoot) {
								log.writeLong((Long)value.value);
							}
						}
					} 

					log.writeLong(0);
					log.writeUTF(Operation.EOF.toString());
					log.flush();
				}	
			}
						
			for (Map.Entry<LogKey, LogValue> entry : batch.entrySet()) {
				LogKey key = entry.getKey();
				LogValue value = entry.getValue();
				
				switch (key.op) {
				case EOF:
					break;
				case Release:
					this.freeNode(key.id);
					break;
				case SetRoot:
					this.header.root = (long)value.value;
					this.writeHeader();
					break;
				case Update:
					this.file.writeBlock(((TreeNode)value.value).serialize(), key.id);
					break;
				}
			}
			
			this.logFile.delete();
		} catch (Exception exc) {
			MetricsLogger.getInstance().logAlert("There was an error flushing the database");
			exc.printStackTrace();
			logger.error("Flush failed " + exc.getMessage());
		} finally {
			this.flushLock.unlock();
		}
	}
	
	public void flushAll() {
		this.lock.lock();
		
		try {
			this.flushPrevious();
			this.flush(this.currentBatch);
			
			this.header.flushed = true;
			this.writeHeader();
		} catch (Exception exc) {
			logger.error("Error flushing header " + exc.getMessage());
		} finally {
			this.lock.unlock();
		}
	}
	
	private void flushPrevious() {
		this.lock.lock();
		
		try {
			if (this.prevBatch != null && !this.isClosed) {
				this.flush(this.prevBatch);
				this.prevBatch = null;
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
			
			List<Map.Entry<LogKey, Object>> batch = new LinkedList<>();
			
			for (int idx = 0; idx < count; idx++) {
				long id = stream.readLong();
				Operation op = Enum.valueOf(Operation.class, stream.readUTF());
				Object value = null;
				
				switch (op) {
				case SetRoot:
					value = stream.readLong();
					break;
				case Update:
					byte[] bytes = new byte[stream.readInt()];
					
					stream.read(bytes);
					value = bytes;
					break;
				default:
					break;
				}
				
				batch.add(new SimpleEntry<>(new LogKey(op, id), value));
			}
			
			if (batch.isEmpty()) {
				return;
			}
			
			if (batch.getLast().getKey().op != Operation.EOF) {
				logger.info("Tree log was not properly closed");
				return;
			}
			
			for (Map.Entry<LogKey, Object> entry : batch) {
				LogKey key = entry.getKey();
				
				switch (key.op) {
				case SetRoot:
					this.header.root = (long)entry.getValue();
					this.writeHeader();
					break;
				case Update:
					this.file.writeBlock((byte[])entry.getValue(), key.id);
					break;
				default:
					break;
				}
			}
		} catch (EOFException exc) {
			MetricsLogger.getInstance().logAlert("The index log was not properly closed");
			logger.info("The index log was not properly closed");
		} catch (Exception exc) {
			MetricsLogger.getInstance().logAlert("There was an error recovering database");
			logger.error("Recovery failed " + exc.getMessage());
		}
	}
	
	public void releaseNode(long id) throws IOException {
		
		threadChanges.get().put(new LogKey(Operation.Release, id), new LogValue(NULL_NODE, false));
	}
	
	
	public void rollback() {
		for (Map.Entry<LogKey, LogValue> entry : threadChanges.get().entrySet()) {
			cache.invalidate(new NodeId(this, entry.getKey().id));
		}
		
		threadChanges.get().clear();
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
			logger.error("Exception freeing node " + exc.getMessage());
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
	
	public long getDocumentCount() {
		return this.header.documentCount;
	}
	
	public GUID getHighGUID()throws IOException {
		return this.header.highGUID;
	}
	
	public Metadata getMetadata() throws IOException {
		if (this.header.metadataOffset == -1 || this.header.metadataSize == 0) {
			logger.info("Bad metadata  offset = " + this.header.metadataOffset + " size = " + this.header.metadataSize);
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
			byte[] bytes = this.file.readBlock(id);
			TreeNode node = new TreeNode(bytes);

			cache.put(nodeId, node);

			return node;
		}
			
	}

	public int getNodeSize() {
		return this.blockSize;
	}
	
	public boolean isNew() {
		return this.file.isNew();
	}
	
	public void saveRoot(long id) throws IOException {
		
		threadChanges.get().put(ROOT_KEY, new LogValue(id, false));
	}
	
	public void saveNode(TreeNode node) throws Exception {
		NodeId id = new NodeId(this, node.getId());
		
		cache.put(id, node);	
		threadChanges.get().put(new LogKey(Operation.Update, node.getId()), new LogValue(new TreeNode(node), false));
		
	}

	public long getReadCount() {
		return this.file.getReadCount();
	}
	
	
	public long getWriteCount() {
		return this.file.getWriteCount();
	}
	
	public long getRoot() {
		return this.header.root;
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
	
	public void setDocumentCount(long documentCount) throws Exception {
		this.header.documentCount = documentCount;
		
		this.writeHeader();
	}
	
	public void setHighGUID(GUID guid) throws Exception {
		this.header.highGUID = guid;
		this.writeHeader();
	}
	
	public void setMetadata(Metadata metadata) throws Exception {
		byte[] bytes = MAPPER.writeValueAsBytes(metadata);
		this.header.metadataOffset = this.file.append(bytes);
		this.header.metadataSize = bytes.length;
		
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
		GUID highGUID = GUID.MINIMUM;
		long metadataOffset = -1;
		int metadataSize = 0;
		boolean flushed = false;
		long createTime = 0;
		long documentCount = 0;
		
		Header() {
		}
		
		Header(TypedInputStream stream) throws Exception {
			this.magic = stream.readUTF();
			this.root = stream.readLong();
			this.freeBlock = stream.readLong();
			this.bloomOffset = stream.readLong();
			this.bloomSize = stream.readInt();
			this.metadataOffset = stream.readLong();
			this.metadataSize = stream.readInt();
			this.flushed = stream.readBoolean();
			this.createTime = stream.readLong();
			this.documentCount = stream.readLong();
			this.highGUID = new GUID().deserialize(stream);
			
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
				
				stream.writeLong(this.metadataOffset);
				stream.writeInt(this.metadataSize);
				stream.writeBoolean(this.flushed);
				stream.writeLong(this.createTime);
				stream.writeLong(this.documentCount);
				
				this.highGUID.serialize(stream);
				
				return stream.toByteArray();
			}			
		}

		@Override
		public String toString() {
			return "Header [root=" + root + ", freeBlock=" + freeBlock + ", bloomOffset=" + bloomOffset +  ", flushed=" + flushed + "]";
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
