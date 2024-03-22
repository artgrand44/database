package org.art.db.storage;

import java.io.Closeable;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Arrays;

import org.art.db.common.LogEntry;
import org.art.db.common.TypedOutputStream;

/**
 * A stream for writing log entries.  It optimizes performance by increasing
 * the length of the log in large chunks and filling the space at the end
 * with -1's.  This guarantees that the stream that reads the log will
 * detect an invalid object type and terminate the scan.
 */
public class LogOutputStream  implements Closeable{
	
	/**
	 * The real length of the file (based on how much data has been written)
	 */
	private long realLength = 0;
	
	/**
	 * The length recorded in the file system.  This may be equal to or less than the
	 * real length depending on how the OS implements FileOutputStream.flush()
	 */
	private long osLength;
	
	/**
	 * The underlying data stream
	 */
	private final FileOutputStream fileStream;
	
	/**
	 * The associated channel
	 */
	private final FileChannel channel;
	
	/**
	 * The block size for extending the file
	 */
	private final static int BLOCK_SIZE = 1024 * 2024;
	
	public LogOutputStream(File file) throws FileNotFoundException {
		this.fileStream = new FileOutputStream(file);
		this.channel = this.fileStream.getChannel();
	}
	
	private void insureEOF(int count) throws IOException {
		long newEOF = this.realLength + count;
		
		if (this.osLength - newEOF < BLOCK_SIZE) {
			long newOsLength = this.osLength + BLOCK_SIZE;
			long currentPosition = this.channel.position();
			byte[] fill = new byte[(int)(newOsLength - currentPosition)];
			
			Arrays.fill(fill, (byte)-1);
			
			ByteBuffer buffer = ByteBuffer.wrap(fill);
			int offset = 0;
			
			while (buffer.position() < buffer.limit()) {
				offset += this.channel.write(buffer, currentPosition + offset);
			}

			this.channel.force(false);
			
			this.channel.position(currentPosition);		
			
			this.osLength = newOsLength;
		}
		
		this.realLength = newEOF;
	}
	
	@Override
	public void close() throws IOException {
		this.fileStream.close();		
	}

	public void write(LogEntry entry) throws Exception {
		try (TypedOutputStream stream = new TypedOutputStream()) {
			stream.writeObject(entry);
			
			byte[] bytes = stream.toByteArray();
			
			this.insureEOF(bytes.length + 5);
			
			this.fileStream.write(bytes);
		}
	}

}
