package org.art.db.storage;

import java.io.Closeable;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Arrays;
import java.util.concurrent.locks.ReentrantLock;

import org.art.db.common.LogEntry;
import org.art.db.common.TypedOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A stream for writing log entries.  It optimizes performance by increasing
 * the length of the log in large chunks and filling the space at the end
 * with -1's.  This guarantees that the stream that reads the log will
 * detect an invalid object type and terminate the scan.
 */
public class LogOutputStream  implements Closeable{
	
	@SuppressWarnings("unused")
	private static final Logger logger = LoggerFactory.getLogger(LogOutputStream.class);
	
	private long nextOffset = 0;
	
	/**
	 * The size as recorded in the file system
	 */
	private long lastOSBlock = -1;
	
	/**
	 * The offset for the next record
	 */
	private final FileOutputStream fileStream;
	
	/**
	 * The associated channel
	 */
	private final FileChannel channel;
	
	private final ReentrantLock lock = new ReentrantLock();
	
	
	/**
	 * The block size for extending the file
	 */
	private final static int BLOCK_SIZE = 1024 * 2024;
	
	public LogOutputStream(File file) throws FileNotFoundException {
		this.fileStream = new FileOutputStream(file);
		this.channel = this.fileStream.getChannel();
	}
	
	private long getOffset(int length) throws IOException {
		this.lock.lock();
			
		long result = this.nextOffset;
		nextOffset += length;
		
		try {
			long lastBlock = (result + length) / BLOCK_SIZE;
			
			if (lastBlock > this.lastOSBlock) {
				byte[] fill = new byte[BLOCK_SIZE];
				
				Arrays.fill(fill, (byte)-1);
				
				this.write(fill, this.lastOSBlock + BLOCK_SIZE);
				this.lastOSBlock = lastBlock;
				this.channel.force(false);
			}			
		} finally {
			this.lock.unlock();
		}
		
		return result;
	}
	
	@Override
	public void close() throws IOException {
		this.fileStream.close();		
	}

	public long getLength() {
		return this.lastOSBlock * BLOCK_SIZE;
	}
	
	private void write(byte[] bytes, long offset) throws IOException {
		ByteBuffer buffer = ByteBuffer.wrap(bytes);
		
		while (buffer.position() < buffer.limit()) {
			offset += this.channel.write(buffer, offset);
		}
	}
	
	public void write(LogEntry entry) throws Exception {
		try (TypedOutputStream stream = new TypedOutputStream()) {
			stream.writeObject(entry);
						
			byte[] bytes = stream.toByteArray();
			long offset = this.getOffset(bytes.length);
			
			this.write(bytes, offset);
		} catch (Exception exc) {
			Metrics.getInstance().logAlert("There was an error writing to the log");		
		}
	}

}
