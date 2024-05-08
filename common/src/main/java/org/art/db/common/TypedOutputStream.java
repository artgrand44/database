package org.art.db.common;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.sql.Date;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;


public class TypedOutputStream extends DataOutputStream {
	
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
	
	static enum Types {
		Boolean,
		Byte,
		Character,
		Double,
		Float,
		Integer,
		Long,
		Short,
		String,
		Document,
		Key,
		List,
		ArrayList,
		LinkedList,
		DocumentSet,
		DBNull,
		Blob,
		GUID,
		Date,
		LogEntry,
	}
	
	private static final Map<Object, Integer> sizes = Map.of(
			Boolean.class,   1,
			Byte.class,      1,
			Character.class, 2,
			Double.class,    8,
			Float.class,     4,
			Integer.class,   2,
			Short.class,     2,
			Long.class,      8,
			DBNull.class,    0,
			Date.class,      8);
	
	public TypedOutputStream() {
		super(new ByteArrayOutputStream());
	}
	
	public TypedOutputStream(OutputStream out) {
		super(out);
	}
	
	public TypedOutputStream(File file) throws FileNotFoundException {
		super(new BufferedOutputStream(new FileOutputStream(file)));
	}
	
	public static boolean isSerializable(Object obj) {
		return isSerializable(obj.getClass());
	}

	public static boolean isSerializable(Class<?> clazz) {
		
		for (int idx = 0; idx < SUPPORTED_TYPES.length; idx++) {			
			if (clazz == SUPPORTED_TYPES[idx]) {
				return true;
			}
		}
		
		return false;
	}
	
	public byte[] toByteArray() throws IOException {
		if (this.out instanceof ByteArrayOutputStream bout) {
			this.flush();
			bout.flush();
			return bout.toByteArray();
		} else {
			throw new IOException("Invalid toByteArray call");
		}
	}
	
	
	@SuppressWarnings("rawtypes")
	public void writeObject(Object obj) throws IOException {
		Class<?> clazz = obj.getClass();
		
		if (clazz == Boolean.class) {
			this.writeByte(Types.Boolean.ordinal());
			this.writeBoolean((Boolean)obj);
		} else if (clazz == Byte.class) {
			this.writeByte(Types.Byte.ordinal());
			this.writeByte((Byte)obj);
		} else if ((clazz == Character.class)) {
			this.writeByte(Types.Character.ordinal());
			this.writeChar((Character)obj);
		} else if (clazz == Double.class) {
			this.writeByte(Types.Double.ordinal());
			this.writeDouble((Double)obj);
		} else if (clazz == Float.class) {
			this.writeByte(Types.Float.ordinal());
			this.writeFloat((Float)obj);
		} else if (clazz == Integer.class) {
			this.writeByte(Types.Integer.ordinal());
			this.writeInt((Integer)obj);
		} else if (clazz == Long.class) {
			this.writeByte(Types.Long.ordinal());
			this.writeLong((Long)obj);
		} else if (clazz == Short.class) {
			this.writeByte(Types.Short.ordinal());
			this.writeShort((Short)obj);
		} else if (clazz == Date.class) {
			this.writeByte(Types.Date.ordinal());
			this.writeLong((((Date)obj).getTime()));
		} else if (clazz == String.class) {
			byte[] bytes = ((String)obj).getBytes();
			
			this.writeByte(Types.String.ordinal());
			this.writeInt(bytes.length);
			this.write(bytes);
		} else if (clazz == LogEntry.class) {
			this.writeByte(Types.LogEntry.ordinal());
			((LogEntry)obj).serialize(this);
		} else if (List.class.isAssignableFrom(clazz)) {
			List list = (List)obj;
			
			this.writeByte(Types.List.ordinal());
			this.writeInt(list.size()); 
			
			for (Object item : list) {
				this.writeObject(item);
			}
		} else if (clazz == DocumentSet.class) {
			this.writeByte(Types.DocumentSet.ordinal());
			((DocumentSet)obj).serialize(this);
		} else if (clazz == Key.class) {
			this.writeByte(Types.Key.ordinal());
			((Key)obj).serialize(this);
		} else if (clazz == Document.class) {
			this.writeByte(Types.Document.ordinal());
			((Document)obj).serialize(this);
		} else if (clazz == DBNull.class) {
			this.writeByte(Types.DBNull.ordinal());
		} else if (clazz == Blob.class) {
			this.writeByte(Types.Blob.ordinal());
			((Blob)obj).serialize(this);
		} else if (clazz == GUID.class) {
			this.writeByte(Types.GUID.ordinal());
			((GUID)obj).serialize(this);
		} else {
			throw new IOException("Illegal field type");
		}
		
	}
		
	public static int getSize(Object obj) {
		if (obj == null) {
			return 0;
		}
		
		if (obj instanceof String str) {
			return str.getBytes().length + 5;
		} else if (obj instanceof List list) {
			int size = 5;
			
			for (Object item : list) {
				size += getSize(item);
			}
			
			return size;
		} else if (obj instanceof Key key) {
			return key.getSize();
		} else if (obj instanceof Document doc) {
			return doc.getSize() + 5;
		} else if (obj instanceof Blob blob) {
			return blob.getSize() + 5;
		} else if (obj instanceof GUID guid) {
			return guid.getSize() + 5;
		} else if (obj instanceof LogEntry entry) {
			return entry.getSize() + 5;
		} else {
			return sizes.get(obj.getClass()) + 1;
		}
		
	}
}
