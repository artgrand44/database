package org.art.db.common;

import java.io.EOFException;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;

public record LogEntry(Operation op, Key key, Document document, long timestamp, boolean propagate, CompletableFuture<Boolean> future) {
	
	public enum Operation {
		INSERT,
		REMOVE,
		UPDATE
	}
	
	public LogEntry(Operation op, Key key, Document document, long timestamp, boolean propagate) {
		this(op, key, document, timestamp, propagate, new CompletableFuture<>());
	}
	
	static LogEntry read(TypedInputStream inputStream) throws IOException {
		try {
			Key key = (Key)inputStream.readObject();
			long timestamp = inputStream.readLong();
			Operation op = Enum.valueOf(Operation.class,inputStream.readUTF());
			boolean propagate = inputStream.readBoolean();
			Document doc = null;
			
			if (inputStream.readBoolean()) {
				doc = (Document)inputStream.readObject();
			} 
			
			return new LogEntry(op, key, doc, timestamp, propagate);
		} catch (EOFException exc) {
			return null;
		}
	}
	
	void write(TypedOutputStream stream) throws IOException {
		stream.writeObject(this.key);
		stream.writeLong(this.timestamp);
		stream.writeUTF(this.op.toString());
		stream.writeBoolean(this.propagate);
		
		if (this.document == null) {
			stream.writeBoolean(false);
		} else {
			stream.writeBoolean(true);
			stream.writeObject(this.document);
		}
		
		stream.flush();
	}
}
