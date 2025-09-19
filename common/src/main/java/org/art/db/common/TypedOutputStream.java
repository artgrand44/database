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
import java.util.concurrent.atomic.AtomicInteger;


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
	
	public TypedOutputStream(File file) throws FileNotFoundException {
		super(new BufferedOutputStream(new FileOutputStream(file)));
	}
	
	public TypedOutputStream(OutputStream out) {
		super(out);
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
	
	private void writeList(List<?> list) throws IOException {
		this.writeInt(list.size()); 
		
		for (Object item : list) {
			this.writeObject(item);
		}
	}
	
	public void writeObject(Object obj) throws IOException {
		
		switch (obj) {
		case Boolean bool ->    { this.writeByte(StreamType.Boolean.ordinal());     this.writeBoolean(bool); }
		case Byte b ->          { this.writeByte(StreamType.Byte.ordinal());        this.writeByte(b); }
		case Character chr ->   { this.writeByte(StreamType.Character.ordinal());   this.writeChar(chr); }
		case Double dbl ->      { this.writeByte(StreamType.Double.ordinal());      this.writeDouble(dbl); }
		case Float fl ->        { this.writeByte(StreamType.Float.ordinal());       this.writeFloat(fl); }
		case Integer in ->      { this.writeByte(StreamType.Integer.ordinal());     this.writeInt(in); }
		case Long lng ->        { this.writeByte(StreamType.Long.ordinal());        this.writeLong(lng); }
		case Short shrt ->      { this.writeByte(StreamType.Long.ordinal());        this.writeShort(shrt); }
		case Date date ->       { this.writeByte(StreamType.Date.ordinal());        this.writeLong(date.getTime()); }
		case LogEntry entry ->  { this.writeByte(StreamType.LogEntry.ordinal());    entry.serialize(this); }
		case Document doc ->    { this.writeByte(StreamType.Document.ordinal());    doc.serialize(this); }
		case DocumentSet set -> { this.writeByte(StreamType.DocumentSet.ordinal()); set.serialize(this); }
		case Key key ->         { this.writeByte(StreamType.Key.ordinal());         key.serialize(this); }
		case Blob blob ->       { this.writeByte(StreamType.Blob.ordinal());        blob.serialize(this); }
		case GUID guid ->       { this.writeByte(StreamType.GUID.ordinal());        guid.serialize(this); }
		case String str ->      { this.writeByte(StreamType.String.ordinal());      this.writeString(str); }
		case List<?> list ->    { this.writeByte(StreamType.List.ordinal());        this.writeList(list); }
		case DBNull _ ->       { this.writeByte(StreamType.DBNull.ordinal()); }
		
		default ->              throw new IOException("Illegal field type");			
		}
	
	}
	
	private void writeString(String string) throws IOException {
		byte[] bytes = string.getBytes();
		
		this.writeInt(bytes.length);
		this.write(bytes);
	}
		
	public static int getSize(Object obj) {
		if (obj == null) {
			return 0;
		}
		
		Class<?> clazz = obj.getClass();
		
		if (obj instanceof String str) {
			return str.getBytes().length + 5;
		}
		
		if (sizes.containsKey(clazz)) {
			return sizes.get(clazz) + 1;
		} 
		
		if (Serializable.class.isAssignableFrom(clazz)) {
			return ((Serializable<?>)obj).getSize();
		} 

		if (List.class.isAssignableFrom(clazz)) {
			List<?> list = (List<?>)obj;
			
			AtomicInteger size = new AtomicInteger(5);
			
			list.forEach(x -> size.addAndGet(getSize(x)));
			
			return size.intValue();
		} 
		
		throw new DBRuntimeException("Illegal field type");				
	}
	
	public static boolean isSerializable(Object obj) {
		Class<?> clazz = obj.getClass();
				
		for (int idx = 0; idx < SUPPORTED_TYPES.length; idx++) {			
			if (clazz == SUPPORTED_TYPES[idx]) {
				return true;
			}
		}
		
		return false;
	}
}
