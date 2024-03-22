package org.art.db.storage;

import java.io.Closeable;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Arrays;

import org.art.db.common.TypedOutputStream;

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
	private final DataOutputStream dataStream;
	
	/**
	 * The associated channel
	 */
	private final FileChannel channel;
	
	/**
	 * The block size for extending the file
	 */
	private final static int BLOCK_SIZE = 1024 * 2024;
	
	public LogOutputStream(File file) throws FileNotFoundException {
		FileOutputStream stream = new FileOutputStream(file);
		this.dataStream = new DataOutputStream(stream);
		this.channel = stream.getChannel();
	}
	
	private void insureEOF(int count) throws IOException {
		long newEOF = this.realLength + count;
		
		if (this.osLength - newEOF < BLOCK_SIZE) {
			long newOsLength = this.osLength + BLOCK_SIZE;
			long currentPosition = this.channel.position();
			byte[] fill = new byte[(int)(newOsLength - currentPosition)];
			
			Arrays.fill(fill, (byte)0);
			
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
		this.dataStream.close();		
	}

	public void writeObject(Object obj) throws Exception {
		try (TypedOutputStream stream = new TypedOutputStream()) {
			stream.writeObject(obj);
			
			byte[] bytes = stream.toByteArray();
			
			this.insureEOF(bytes.length + 5);
			
			this.dataStream.writeByte(1);
			this.dataStream.writeInt(bytes.length);
			this.dataStream.write(bytes);
		}
	}

	public static void writeObject(Object obj, DataOutputStream dstream) throws Exception {
		try (TypedOutputStream stream = new TypedOutputStream()) {
			stream.writeObject(obj);
			
			byte[] bytes = stream.toByteArray();
			
			dstream.writeByte(1);
			dstream.writeInt(bytes.length);
			dstream.write(bytes);
		}
	}
}
