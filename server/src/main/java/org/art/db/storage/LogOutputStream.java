package org.art.db.storage;

import java.io.Closeable;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

import org.art.db.common.LogEntry;
import org.art.db.common.MultiValueMap;
import org.art.db.common.TypedOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A stream for writing log entries.  It optimizes performance by increasing
 * the length of the log in large chunks and filling the space at the end
 * with -1's.  This guarantees that the stream that reads the log will
 * detect an invalid object type and terminate the scan.
 * 
 * Log entries are written in batches corresponding to the batches that are 
 * received from the client.  This reduces the i/o's to the log by at least 
 * 2:1 while still honoring the contract with applications. 
 */
public class LogOutputStream  implements Closeable{
	
	//@SuppressWarnings("unused")
	private static final Logger logger = LoggerFactory.getLogger(LogOutputStream.class);
	
	
	/**
	 * The last block as recorded in the file system
	 */
	private long lastOSBlock = -1;
	
	private volatile boolean closed = true;
	
	/**
	 * The underlying data stream
	 */
	private final FileOutputStream fileStream;
	
	/**
	 * The associated channel
	 */
	private final FileChannel channel;
	
	private final ReentrantLock lock = new ReentrantLock();
	
	static class Batch {
		MultiValueMap<LogOutputStream, LogEntry> entries = new MultiValueMap<LogOutputStream, LogEntry>();
		int level;
	}
	
	private static final ThreadLocal<Batch> batches = ThreadLocal.withInitial(() -> new Batch());
	
	/**
	 * The block size for extending the file
	 */
	private final static int BLOCK_SIZE = 1024 * 2024;
	
	public LogOutputStream(File file) throws FileNotFoundException {
		this.fileStream = new FileOutputStream(file);
		this.channel = this.fileStream.getChannel();
		this.closed = false;
	}
	
	private void insureEOF(int count) throws IOException {
		if (!this.channel.isOpen()) {
			logger.info("channel closed " + this.closed);
		}
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
	public  void close() throws IOException {
		this.closed = true;
		this.fileStream.close();		
	}

	/**
	 * This method is called by the dispatcher at the end of every request.
	 * If the request is a top-level request (as opposed to a recursive 
	 * call to the dispatcher), it flushes all of the log entries for
	 * the thread.
	 * @throws Exception
	 */
	public static void endBatch() throws Exception {
		try {
			Batch batch = batches.get();

			batch.level--;
			
			if (batch.level <= 1) {
				
				// Iterate over the logs used by this thread and try to flush them.
				// If a log is closed, we assume that the corresponding segment has 
				// been safe-stored or copied to a newer segment, so we skip the log.
				// If we get an exception, we assume that the log was closed just as
				// we were starting to flush it.
				
				for (Map.Entry<LogOutputStream, List<LogEntry>> pair : batch.entries.entrySet()) {
					LogOutputStream stream = pair.getKey();
					List<LogEntry> list = pair.getValue();

					if (!stream.closed) {
						stream.writeBatch(list);
						list.clear();
					}

				}
			}
		} catch (Exception exc) {
			logger.info("Flush exception " + exc.getMessage());
		}
	}
	
	public long getLength() {
		return this.lastOSBlock * BLOCK_SIZE;
	}
	
	/**
	 * Mark the beginning of a batchß
	 */
	public static void startBatch() {
		batches.get().level++;
	}
	
	public void write(LogEntry entry) throws Exception {
		batches.get().entries.add(this, entry);
	}

	private void writeBatch(List<LogEntry> batch) throws Exception {
		byte[] bytes = null;
		
		try (TypedOutputStream stream = new TypedOutputStream()) {
			for (LogEntry entry : batch) {
				stream.writeObject(entry);
			}
			
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
