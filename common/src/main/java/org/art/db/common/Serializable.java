package org.art.db.common;

import java.io.IOException;

public abstract class Serializable<T> {
	public abstract void serialize(TypedOutputStream stream) throws IOException;
	
	public abstract T deserialize(TypedInputStream stream) throws IOException;
	
	
	public int getSize() {
		try (TypedOutputStream stream = new TypedOutputStream()) {
			this.serialize(stream);
			
			return stream.toByteArray().length;
		} catch (Exception exc) {
			throw new IllegalStateException("There was an error gettin the size", exc);
		}
	}
	
}
