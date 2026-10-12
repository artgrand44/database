package org.art.db.common;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.sql.Date;
import java.util.ArrayList;
import java.util.List;

public class TypedInputStream extends DataInputStream {
	

	public TypedInputStream(File file) throws FileNotFoundException {
		super(new BufferedInputStream(new FileInputStream(file)));
	}
	
	public TypedInputStream(InputStream in) {
		super(in);
	}

	public TypedInputStream(byte[] bytes) {
		super(new ByteArrayInputStream(bytes));
	}
	
	public InputStream getStream() {
		return this.in;
	}
	
	private List<?> readList() throws IOException {
		List<Object> list = new ArrayList<>();
		int size = this.readInt();
		
		for (int idx = 0; idx < size; idx++) {
			list.add(this.readObject());
		}
		
		return list;
	}
	
	@SuppressWarnings("resource")
	public Object readObject() throws IOException {
		StreamType type = StreamType.values()[this.readByte()];
		
		return switch (type) {
		case Boolean ->     this.readBoolean();
		case Byte ->        this.readByte();
		case Character ->   this.readChar();
		case Double ->      this.readDouble();
		case Float ->       this.readFloat();
		case Integer ->     this.readInt();
		case Long ->        this.readLong();
		case Short ->       this.readShort();
		case String ->      this.readString();
		case Document ->    new Document().deserialize(this);
		case Key ->         new Key().deserialize(this);
		case List ->        this.readList();
		case DocumentSet -> new DocumentSet().deserialize(this); 
		case DBNull ->      DBNull.NULL;
		case Blob ->        new Blob().deserialize(this);
		case GUID ->        new GUID().deserialize(this);
		case Date ->        new Date(this.readLong());
		case LogEntry ->    new LogEntry().deserialize(this);
		case ClientArgs ->  new ClientArgs().deserialize(this);
		case Batch ->  		new Batch().deserialize(this);
		default ->          null;
		};
	}
	
	private String readString() throws IOException {
		int length = this.readInt();
		byte[] bytes = new byte[length];
		
		this.read(bytes);
		
		return new String(bytes);
	}
}
