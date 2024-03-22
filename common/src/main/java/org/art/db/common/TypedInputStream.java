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
import java.util.LinkedList;
import java.util.List;



public class TypedInputStream extends DataInputStream {

	private static final Class<?>[] SUPPORTED_TYPES = {
			Boolean.class,
			Byte.class,
			Character.class,
			Double.class,
			Float.class,
			Integer.class,
			Long.class,
			Short.class,
			String.class,
			Document.class,
			Key.class,
			List.class,
			ArrayList.class,
			LinkedList.class,
			DocumentSet.class,
			DBNull.class,
			Blob.class,
			GUID.class,
			Date.class,
			LogEntry.class,
	};
	
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
	
	@SuppressWarnings({ "rawtypes", "unchecked", "resource" })
	public Object readObject() throws IOException {
		int typeIndex = this.readByte();
		Class<?> clazz = SUPPORTED_TYPES[typeIndex];
		
		if (clazz == Boolean.class) {
			return this.readBoolean();
		} else if (clazz == Byte.class) {
			return this.readByte();
		} else if ((clazz == Character.class)) {
			return this.readChar();
		} else if (clazz == Double.class) {
			return this.readDouble();
		} else if (clazz == Float.class) {
			return this.readFloat();
		} else if (clazz == Integer.class) {
			return this.readInt();
		} else if (clazz == Long.class) {
			return this.readLong();
		} else if (clazz == Short.class) {
			return this.readShort();
		} else if (clazz == Date.class) {
			return new Date(this.readLong());
		} else if (clazz == LogEntry.class) {
			return LogEntry.read(this);
		} else if (clazz == String.class) {
			int length = this.readInt();
			byte[] bytes = new byte[length];
			
			this.read(bytes);
			
			return new String(bytes);
		} else if (List.class.isAssignableFrom(clazz)) {
			List list = new ArrayList();
			int size = this.readInt();
			
			for (int idx = 0; idx < size; idx++) {
				list.add(this.readObject());
			}
			
			return list;
		} else if (clazz == DocumentSet.class) {
			return new DocumentSet().deserialize(this); 
		} else if (clazz == Document.class) {
			return new Document().deserialize(this);
		} else if (clazz == Key.class) {
			return new Key().deserialize(this);
		} else if (clazz == DBNull.class) {
			return DBNull.NULL;
		} else if (clazz == Blob.class) {
			return new Blob().deserialize(this);
		} else if (clazz == GUID.class) {
				return new GUID().deserialize(this);
		} else {
			throw new IOException("Illegal field type");
		}		

	}
	
}
