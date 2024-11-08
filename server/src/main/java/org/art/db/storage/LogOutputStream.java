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
	
	
	/**
	 * The last block as recorded in the file system
	 */
	private long lastOSBlock = -1;
	
	/**
	 * The underlying data stream
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
	
	private void insureEOF(int count) throws IOException {
		long currentPosition = this.channel.position();
		long lastBlock = (currentPosition + count) / BLOCK_SIZE;
		
		if (lastBlock > this.lastOSBlock) {
			byte[] fill = new byte[BLOCK_SIZE];
			
			this.channel.position(lastBlock);
			
			Arrays.fill(fill, (byte)-1);
			
			ByteBuffer buffer = ByteBuffer.wrap(fill);
			int offset = 0;
			
			while (buffer.position() < buffer.limit()) {
				offset += this.channel.write(buffer, currentPosition + offset);
			}

			this.channel.force(true);
			
			this.channel.position(currentPosition);		
			this.lastOSBlock = lastBlock;			
		}

	}
	
	@Override
	public void close() throws IOException {
		this.fileStream.close();		
	}

	public long getLength() {
		return this.lastOSBlock * BLOCK_SIZE;
	}
	
	public void write(LogEntry entry) throws Exception {
		byte[] bytes = null;
		
		try (TypedOutputStream stream = new TypedOutputStream()) {
			stream.writeObject(entry);
			
			bytes = stream.toByteArray();
		}
			
		this.lock.lock();
		
		try {
			
			this.insureEOF(bytes.length + 5);
			this.fileStream.write(bytes);
			this.fileStream.flush();
		} catch (Exception exc) {
			MetricsLogger.getInstance().logAlert("There was an error writing to the log");
			throw exc;
		} finally {
			this.lock.unlock();
		}
	}

}
