package org.art.db.storage;

import java.io.Closeable;
import java.io.EOFException;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;

import org.art.db.common.Document;
import org.art.db.common.Key;
import org.art.db.common.TypedInputStream;
import org.art.db.common.TypedOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class SegmentLog implements Closeable {
	
	@SuppressWarnings("unused")
	private static final Logger logger = LoggerFactory.getLogger(SegmentLog.class);

	enum Operation {
		INSERT,
		REMOVE,
		UPDATE
	}
	
	
	private final TypedInputStream inputStream;
	private final TypedOutputStream outputStream;
	
	SegmentLog(File file, boolean write) throws Exception {
		this.inputStream = write ? null : new TypedInputStream(file);
		this.outputStream = write ? new TypedOutputStream(new EOFFileOutputStream(file)) : null;
	}
	
	SegmentLog(TypedInputStream stream) {
		this.inputStream = stream;
		this.outputStream = null;
	}
	
	@Override
	public void close() throws IOException {
		if (this.inputStream != null) {
			this.inputStream.close();
		}
		
		if (this.outputStream != null) {
			this.outputStream.close();
		}
		
	}
	
	void flush() throws IOException {
		this.outputStream.flush();		
	}
	
	LogEntry read() throws IOException {
		try {
			Key key = (Key)this.inputStream.readObject();
			long timestamp = this.inputStream.readLong();
			Operation op = Enum.valueOf(Operation.class, this.inputStream.readUTF());
			boolean propagate = this.inputStream.readBoolean();
			Document doc = null;
			
			if (this.inputStream.readBoolean()) {
				doc = (Document)this.inputStream.readObject();
			} 
			
			return new LogEntry(op, key, doc, timestamp, propagate);
		} catch (EOFException exc) {
			return null;
		}
	}
	
	void write(LogEntry entry) throws IOException {
		this.write(entry, this.outputStream);
	}

	void write(LogEntry entry, TypedOutputStream stream) throws IOException {
		stream.writeObject(entry.key);
		stream.writeLong(entry.timestamp);
		stream.writeUTF(entry.op.toString());
		stream.writeBoolean(entry.propagate);
		
		if (entry.document == null) {
			stream.writeBoolean(false);
		} else {
			stream.writeBoolean(true);
			stream.writeObject(entry.document);
		}
		
		stream.flush();
	}
	
	public static record LogEntry(Operation op, Key key, Document document, long timestamp, boolean propagate, CompletableFuture<Boolean> future) {
		LogEntry(Operation op, Key key, Document document, long timestamp, boolean propagate) {
			this(op, key, document, timestamp, propagate, new CompletableFuture<>());
		}
	}
	
	class EOFFileOutputStream extends FileOutputStream {
		private long realEOF = 0;
		
		private long directoryEOF;
		
		private final FileChannel channel;
		
		private final File file;
		
		private final static int BLOCK_SIZE = 1024 * 2024;
		
		public EOFFileOutputStream(File file) throws FileNotFoundException {
			super(file);
			logger.info("created " + file.getAbsolutePath());
			this.channel = super.getChannel();
			this.file = file;
		}
		
		private void insureEOF(int count) throws IOException {
			long newEOF = this.realEOF + count;
			
			if (this.directoryEOF - newEOF < BLOCK_SIZE) {
				long newDirectoryEOF = this.directoryEOF + BLOCK_SIZE;
				long currentPosition = this.channel.position();
				byte[] fill = new byte[(int)(newDirectoryEOF - currentPosition)];
				
				Arrays.fill(fill, (byte)0xFF);
				
				ByteBuffer buffer = ByteBuffer.wrap(fill);
				int offset = 0;
				
				while (buffer.position() < buffer.limit()) {
					offset += this.channel.write(buffer, currentPosition + offset);
				}

				this.channel.force(false);
				
				///logger.info("forced " + file.getAbsolutePath() + " to " + this.channel.position());
				this.channel.position(currentPosition);		
				
				this.directoryEOF = newDirectoryEOF;
			}
			
			this.realEOF = newEOF;
		}

		public void write(int b) throws IOException {
			this.insureEOF(1);
			super.write(b);
		}
		
		public void write(byte[] b) throws IOException {
			this.insureEOF(b.length);
			//logger.info("writing to " + file.getAbsolutePath() + " count " + b.length + " at " + this.channel.position());
			super.write(b);
			//logger.info("after " + channel.position());
		}
		
		public void write(byte[] b, int off, int len) throws IOException {
			this.insureEOF(len);
			//logger.info("writing to " + file.getAbsolutePath() + " count " + b.length + " at " + this.channel.position());
			super.write(b, off, len);
			//logger.info("after " + channel.position());
		}
	}
	
}
