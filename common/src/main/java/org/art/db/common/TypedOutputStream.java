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
import java.util.List;


public class TypedOutputStream extends DataOutputStream {
	
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
		case DBNull _ ->        { this.writeByte(StreamType.DBNull.ordinal()); }
		
		default ->              throw new IOException("Illegal field type");			
		}
	
	}
	
	private void writeString(String string) throws IOException {
		byte[] bytes = string.getBytes();
		
		this.writeInt(bytes.length);
		this.write(bytes);
	}
		

	private static int calulateSize(Object obj)  {
		try (TypedOutputStream stream = new TypedOutputStream()) {
			stream.writeObject(obj);
			stream.flush();
			
			return stream.toByteArray().length;
		} catch (Exception exc) {
			throw new IllegalStateException(exc);
		}
	}

	public static int getSize(Object obj) {
		if (obj == null) {
			return 0;
		}
		
		return switch (obj) {
		case Boolean _ ->    	1;
		case Byte _ ->          1;
		case Character _ ->   	2;
		case Double _ ->      	8;
		case Float _ ->        	4;
		case Integer _ ->      	4;
		case Long _ ->        	8;
		case Short _ ->      	2;
		case Date _ ->       	8;
		case Key key ->         key.getSize();
		case String str ->      str.getBytes().length + 5;
		case List<?> list ->    getListSize(list);
		case DBNull _ ->        0;
		
		default ->              calulateSize(obj);			
		};
	}
	
	private static int getListSize(List<?> list) {
		int size = 0;
		
		list.forEach(x -> getSize(x));
		
		return size;
	}
	
	public static boolean isSerializable(Object obj) {
		return switch (obj) {
		case Boolean _ ->    	true;
		case Byte _ ->          true;
		case Character _ ->   	true;
		case Double _ ->      	true;
		case Float _ ->        	true;
		case Integer _ ->      	true;
		case Long _ ->        	true;
		case Short _ ->      	true;
		case Date _ ->       	true;
		case LogEntry _ ->      true;
		case Document _ ->      true;
		case DocumentSet _ ->   true;
		case Key _ ->           true;
		case Blob _ ->          true;
		case GUID _ ->          true;
		case String _ ->        true;
		case List<?> _ ->       true;
		case DBNull _ ->        true;
		
		default ->              false;			
		};
	}
}
