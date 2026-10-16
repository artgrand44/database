package org.art.db.common;

import java.io.IOException;

public class LogEntry {
	
	public enum Operation {
		INSERT,
		REMOVE,
		UPDATE,
		UPSERT
	}
	
	private Operation op;
	
	private Key key;
	
	private Document document;
	
	private long timestamp;
	
	private boolean propagate;
	
	public LogEntry() {
		
	}
	public LogEntry(Operation op, Key key, Document document, long timestamp, boolean propagate) {
		this.op = op;
		this.key = key;
		this.document = document;
		this.timestamp = timestamp;
		this.propagate = propagate;
	}
	
	public LogEntry deserialize(TypedInputStream stream) throws IOException {
		this.key = (Key)stream.readObject();
		this.timestamp = stream.readLong();
		this.op = Enum.valueOf(Operation.class,stream.readUTF());
		this.propagate = stream.readBoolean();
		
		if (stream.readBoolean()) {
			this.document = (Document)stream.readObject();
		} 

		return this;

	}
	
	public void serialize(TypedOutputStream stream) throws IOException {
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

	public Operation getOp() {
		return op;
	}

	public Key getKey() {
		return key;
	}

	public Document getDocument() {
		return document;
	}

	public long getTimestamp() {
		return timestamp;
	}

	public boolean isPropagate() {
		return propagate;
	}
	
	@Override
	public String toString() {
		return "LogEntry [op=" + op + ", key=" + key + ", timestamp=" + timestamp + ", propagate=" + propagate  + ", document " + document + "]";
	}

	

	
}
