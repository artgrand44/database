package org.art.db.storage;

import java.io.Closeable;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
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
		this.outputStream = write ? new TypedOutputStream(file) : null;
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
}
