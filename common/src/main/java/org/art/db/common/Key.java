package org.art.db.common;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Key implements Serializable<Key>, Comparable<Key> {
	
	private static final Logger logger = LoggerFactory.getLogger(Key.class);
	
	public static final String MIN = "min".intern();
	
	public static final String MAX = "max".intern();
	public static final String WILD_CARD = "*".intern();
	private Comparable<?>[] keys;
	private int hash;
	private int size;
	
	public Key() {
		this.keys = new Comparable<?>[0];
	}
	
	public Key(Comparable<?> ... keys) {
		Objects.requireNonNull(keys);
		
		int totalSlots = 0;
		
		for (Comparable<?> key : keys) {
			Objects.requireNonNull(key);
			
			totalSlots += (key instanceof Key k ? k.keys.length : 1);
		}
		
		Comparable<?>[] flattened = new Comparable<?>[totalSlots];
		int slot = 0;
				
		for (Comparable<?> key : keys) {
			if (key instanceof Key k) {
				System.arraycopy(k.keys, 0, flattened, slot, k.keys.length);
				slot += k.keys.length;
			} else {
				flattened[slot++] = key;
			}
		}
				
		this.setKeys(flattened);
	
	}
	
	

	public Key(List<Comparable<?>> keys) {
		this(keys.toArray(new Comparable<?>[keys.size()]));	
	}
	
	
	@SuppressWarnings("unchecked")
	private <T extends Comparable<T>> int compareKey(Object key1, Object key2, Class<T> clazz) {
		return ((T)key1).compareTo((T)key2);
	}
	
	@SuppressWarnings("unchecked")
	@Override
	public int compareTo(Key that) {
		if (that == null) {
			logger.info("null key");
		}
		
		final int length = Math.min(this.keys.length, that.keys.length);
		
		for (int idx = 0; idx < length; idx++) {
			final Comparable<?> thisKey = this.keys[idx];
			final Comparable<?> thatKey =  that.keys[idx];
			
			if (thisKey == MIN) {
				return -1;
			} else if (thatKey == MAX) {
				return -1;
			} else if (thatKey == WILD_CARD) {
				return 0;
			} else if (thisKey instanceof DBNull && ! (thatKey instanceof DBNull)) {
				return -1;
			} else if (thatKey instanceof DBNull && ! (thisKey instanceof DBNull)) {
				return +1;
			} else {
				final int comp = this.compareKey(thisKey, thatKey, thisKey.getClass());
				
				if (comp != 0) {
					return comp;
				}
			}			
		}
		
		return this.keys.length - that.keys.length;

	}
	
	@Override
	public Key deserialize(TypedInputStream stream) throws IOException {
		final int count = stream.readInt();
		final Comparable<?>[] keys = new Comparable<?>[count];
		
		for (int idx = 0; idx < count; idx++) {
			keys[idx] = (Comparable<?>)stream.readObject();
		}
		
		this.setKeys(keys);
				
		return this;
	}
	
	
	public Comparable<?>[] getKeys() {
		return keys;
	}

	@Override
	public int hashCode() {
		return this.hash;
	}
	

	@Override
	public int getSize() {
		return this.size;
	}
	
	@Override
	public boolean equals(Object obj) {
		return obj instanceof Key that ? Arrays.equals(this.keys, that.keys) : false;			
	}
	
	public void serialize(TypedOutputStream stream) throws IOException {
		stream.writeInt(this.keys.length);
		
		for (Object key : this.keys) {
			stream.writeObject(key);
		}
	}
	
	public boolean startsWith(Key key) {
		for (int idx = 0; idx < key.keys.length; idx++) {
			if (!this.keys[idx].equals(key.keys[idx])) {
				return false;
			}
		}
		
		return true;
	}
	
	public GUID getGUID() {
		if (!this.isGUID()) {
			throw new IllegalStateException("The key is not a GUID");
		}
		
		return (GUID)this.keys[0];
	}
	
	public boolean isGUID() {
		return this.keys.length == 1 && this.keys[0] instanceof GUID;
	}
	
	public void setKeys(Comparable<?> ...keys) {
		this.keys = keys;
		
		int hash = 125;
		int size = 5;
		
		for (int idx = 0; idx < this.keys.length; idx++)  {
			hash ^= this.keys[idx].hashCode() + idx;
			size += TypedOutputStream.getSize(this.keys[idx]);
		}
		
		this.hash = hash;
		this.size = size;
	}
	
	@Override
	public String toString() {
		return "Key [keys=" + Arrays.toString(keys) + "]";
	}
	
	public Key with(Comparable<?> key) {
		final Comparable<?>[] keys = new Comparable<?>[this.keys.length + 1];
		
		System.arraycopy(this.keys, 0, keys, 0, this.keys.length);
		
		keys[this.keys.length] = key;
		
		return new Key(keys);
		                                      
	}
	
}
