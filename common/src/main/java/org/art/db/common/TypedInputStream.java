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
	
	@SuppressWarnings("resource")
	public Object readObject() throws IOException {
		StreamType type = StreamType.values()[this.readByte()];
		
		switch (type) {
		case Boolean:
			return this.readBoolean();
		case Byte:
			return this.readByte();
		case Character:
			return this.readChar();
		case Double:
			return this.readDouble();
		case Float:
			return this.readFloat();
		case Integer:
			return this.readInt();
		case Long:
			return this.readLong();
		case Short:
			return this.readShort();
		case String:
			int length = this.readInt();
			byte[] bytes = new byte[length];
			
			this.read(bytes);
			
			return new String(bytes);
		case Document:
			return new Document().deserialize(this);
		case Key:
			return new Key().deserialize(this);
		case List:
		case ArrayList:
		case LinkedList:
			List<Object> list = new ArrayList<>();
			int size = this.readInt();
			
			for (int idx = 0; idx < size; idx++) {
				list.add(this.readObject());
			}
			
			return list;
		case DocumentSet:
			return new DocumentSet().deserialize(this); 
		case DBNull:
			return DBNull.NULL;
		case Blob:
			return new Blob().deserialize(this);
		case GUID:
			return new GUID().deserialize(this);
		case Date:
			return new Date(this.readLong());
		case LogEntry:
			return new LogEntry().deserialize(this);
		default:
			throw new DBRuntimeException("Error deserializng " + type);
		}
	}
	
}
