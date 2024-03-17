package org.art.db.common;

import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Document extends Serializable<Document> implements Iterable<Map.Entry<String, Object>>{
	
	private static final Logger logger = LoggerFactory.getLogger(Document.class);
	
	private final Map<String, Object> map = new HashMap<>();
	private Key key;
	
	public Document() {
		
	}
	
	public Document(Document that) {
		this.key = that.key;
		
		for (Map.Entry<String, Object> entry : that.map.entrySet()) {
			String name = entry.getKey();
			Object value = entry.getValue();
			
			if (value instanceof Document doc) {
				this.map.put(name, new Document(doc));
			} else {
				this.map.put(name, value);
			}
		}
	}
	
	@Override
	public Document deserialize(TypedInputStream stream) throws IOException {
		int size = stream.readInt();
		
		for (int idx = 0; idx < size; idx++) {
			String key = stream.readUTF();
			Object value = stream.readObject();
			
			if (value instanceof String str && str.contains("org.art.db.storage.DBNull@")) {
				logger.info("oops");
			}
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

	public void put(String name, Object value) {
		int dot = name.indexOf('.');
		
		if (value instanceof String str && str.contains("org.art.db.storage.DBNull@")) {
			logger.info("oops");
		}
		if (dot == -1) {
			this.map.put(name, value);
		} else {		
			String prefix = name.substring(0,  dot);
			String suffix = name.substring(dot + 1);
			Object existingValue = this.map.get(prefix);
			
			if (existingValue == null || existingValue == DBNull.NULL) {
				Document doc = new Document();
				
				this.map.put(prefix, doc);
				doc.put(suffix, value);
			} else if (existingValue instanceof Document doc) {
				doc.put(suffix, value);
			} else {
				throw new IllegalStateException("Bad complex name " + existingValue);
			}		
		}
	}
	
	public Key getKey() {
		return key;
	}

	public void setKey(Key key) {
		this.key = key;
	}

	@Override
	public String toString() {
		return "Document [map=" + map + "]";
	}
	
	
	
}
