package org.art.db.common;

import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Document implements Serializable<Document>, Iterable<Map.Entry<String, Object>>{
	
	@SuppressWarnings("unused")
	private static final Logger logger = LoggerFactory.getLogger(Document.class);
	
	private Map<String, Object> map = new HashMap<>();
	private boolean zombie;
	
	public final static Document ZOMBIE = new Document(true);
	
	public Document() {
		this(false);
	}
	
	private Document(boolean zombie) {
		this.zombie = zombie;
	}
	
	public Document(Document that) {
		this.zombie = that.zombie;
		
		for (Map.Entry<String, Object> entry : that.map.entrySet()) {
			final String name = entry.getKey();
			final Object value = entry.getValue();
			
			if (value instanceof Document doc) {
				this.map.put(name, new Document(doc));
			} else {
				this.map.put(name, value);
			}
		}
	}
	
	public boolean isZombie() {
		return zombie;
	}

	public void setZombie(boolean zombie) {
		this.zombie = zombie;
	}
	
	@Override
	public Document deserialize(TypedInputStream stream) throws IOException {
		int size = stream.readInt();
		
		for (int idx = 0; idx < size; idx++) {
			final String key = stream.readUTF();
			final Object value = stream.readObject();
			
			this.map.put(key, value);
		}
		
		return this;
	}

	public boolean containsKey(String key) {
		return this.map.containsKey(key);
	}
	
	@Override
	public boolean equals(Object that) {
		if (that instanceof Document thatDoc) {
			return this.map.equals(thatDoc.map);
		}
		
		return false;
	}
	
	@Override
	public int hashCode() {
		return this.map.hashCode();
	}
	
	
	public void serialize(TypedOutputStream stream) throws IOException {
		
		stream.writeInt(this.map.size());
		
		for (Map.Entry<String, Object> entry : this.map.entrySet()) {
			stream.writeUTF(entry.getKey());
			stream.writeObject(entry.getValue());
		}
	}
	

	@Override
	public Iterator<Entry<String, Object>> iterator() {
		return this.map.entrySet().iterator();
	}
	
	public Object get(String name) {
		int dot = name.indexOf('.');
		
		if (dot == -1) {
			return this.map.get(name);
		}
		
		String prefix = name.substring(0,  dot);
		String suffix = name.substring(dot + 1);
		Object value = this.map.get(prefix);
		
		if (value instanceof Document doc) {
			return doc.get(suffix);
		}
		
		return null;
	}

	public <T> T get(String name, Class<T> clazz) {
		Object obj = this.get(name);
		
		if (obj == null) {
			return null;
		} else if (clazz.isAssignableFrom(obj.getClass())) {
			return clazz.cast(obj);
		} else {
			try {
				return clazz.cast(Converter.convert(obj, clazz));
			} catch (Exception exc) {
				throw new DBRuntimeException("The value cannot be converted to " + clazz.getName());
			}
		}
	}
	
	public void put(String name, Object value) {
		int dot = name.indexOf('.');
		
		if (dot == -1) {
			this.map.put(name, value);
		} else {		
			final String prefix = name.substring(0,  dot);
			final String suffix = name.substring(dot + 1);
			final Object existingValue = this.map.get(prefix);
			
			if (existingValue == null || existingValue == DBNull.NULL) {
				final Document doc = new Document();
				
				this.map.put(prefix, doc);
				doc.put(suffix, value);
			} else if (existingValue instanceof Document doc) {
				doc.put(suffix, value);
			} else {
				throw new IllegalStateException("Bad complex name " + existingValue);
			}		
		}
	}
	
	
	@Override
	public String toString() {
		return "Document [map=" + map + "]";
	}

	public Map<String, Object> getMap() {
		return map;
	}

	public void setMap(Map<String, Object> map) {
		this.map = map;
	}	
	
}
