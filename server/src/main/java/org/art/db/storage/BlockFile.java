package org.art.db.storage;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

import org.art.db.common.TypedInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BlockFile implements Closeable {
	private static final Logger logger = LoggerFactory.getLogger(BlockFile.class);
	
	private final int MAX_BUFFERS = 10;
	
	private final int blockSize;
	private final RandomAccessFile file;
	protected final FileChannel channel;
	private final boolean isNew;
	private final ConcurrentLinkedDeque<ByteBuffer> buffers = new ConcurrentLinkedDeque<>();
	private final File rawFile;
	private final ReentrantLock lock = new ReentrantLock();
	
	private AtomicLong readCount = new AtomicLong();
	private AtomicLong writeCount = new AtomicLong();
	
	public BlockFile(File file, int blockSize) throws Exception {
		this.blockSize = blockSize;
		this.isNew = !file.exists();
		this.file = new RandomAccessFile(file, "rwd");
		this.rawFile = file;
		this.channel = this.file.getChannel();
				
	}
	
	
	public List<Long> addBlocks(int count) throws IOException {
		this.lock.lock();
		
		try {
			long length = this.file.length();
			List<Long> result = new LinkedList<>();

			this.file.setLength(length + count * this.blockSize);

			long nextBlock = length / this.blockSize;

			for (int idx = 0; idx < 10; idx++) {
				result.add(nextBlock + idx);
			}

			return result;
		} catch (Exception exc) {
			logger.info("got exception " + this.rawFile.getAbsolutePath());
			throw exc;
		} finally {
			this.lock.unlock();
		}

	}
	
	public long append(byte[] bytes) throws IOException {
		this.lock.lock();
		
		try {
			long position = this.file.length();

			this.file.seek(position);
			this.file.write(bytes);

			return position;
		} finally {
			this.lock.unlock();
		}
	}
	
	private void freeBuffer(ByteBuffer buffer) {
		if (this.buffers.size() < MAX_BUFFERS) {
			buffer.flip();
			this.buffers.add(buffer);
		}
	}
	
	private ByteBuffer getBuffer() {
		ByteBuffer buffer = this.buffers.poll();
		
		return buffer == null ? ByteBuffer.allocate(this.blockSize) : buffer;
	}
	protected long getOffset(long block) {
		return block * this.blockSize;
	}
	
	public long getReadCount() {
		return this.readCount.get();
	}
	
	public long getWriteCount() {
		return this.writeCount.get();
	}
	
	public byte[] readBlock(long block) throws IOException {
		ByteBuffer buffer = this.getBuffer();
		long offset = this.getOffset(block);
		
		while (buffer.position() < this.blockSize) {
			int nread = this.channel.read(buffer, offset);
			
			if (nread < 0) {
				throw new IOException("Attempt to read past end of file");
			} else {
				offset += nread;
			}			
		}
		
		byte[] bytes = new byte[this.blockSize];
		
		buffer.flip();
		buffer.get(bytes);
		
		this.freeBuffer(buffer);
		this.readCount.incrementAndGet();
		return bytes;
	}
	
	
	public TypedInputStream readBlockAsStream(long block) throws IOException {
		return new TypedInputStream(this.readBlock(block));
	}
	
	public synchronized byte[] getBytes(long offset, int length) throws IOException {
		byte[] result = new byte[length];
		
		this.file.seek(offset);
		this.file.read(result);
		
		return result;
	}
	
	public void setLength(long length) throws IOException {
		this.file.setLength(length);
	}
	
	public void writeBlock(byte[] bytes, long block)  throws IOException {
		if (bytes.length > this.blockSize) {
			throw new IOException("Block is too large " + bytes.length);
		}
		
		try {
			ByteBuffer buffer = this.getBuffer();
			byte[] fill = new byte[this.blockSize - bytes.length];

			buffer.put(bytes);
			buffer.put(fill);

			buffer.flip();

			long offset = this.getOffset(block);

			while (buffer.position() < buffer.limit()) {
				offset += this.channel.write(buffer, offset);
			}

			this.channel.force(false);
			this.freeBuffer(buffer);
			this.writeCount.incrementAndGet();
		} catch (Exception exc) {
			exc.printStackTrace();
			logger.info("exception " + exc.getClass().getName() + " for " + rawFile.getAbsolutePath());
		}
	}
	
	
	public boolean isNew() {
		return isNew;
	}

	@Override
	public void close() throws IOException {
		this.channel.close();
		this.file.close();	
	}

	
}
