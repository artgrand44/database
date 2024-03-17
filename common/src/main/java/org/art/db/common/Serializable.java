package org.art.db.common;

import java.io.IOException;

public abstract class Serializable<T> {
	private boolean isSizeValid = false;
	private int size;
	
	public abstract void serialize(TypedOutputStream stream) throws IOException;
	
	public abstract T deserialize(TypedInputStream stream) throws IOException;
	
	protected void clearSize() {
		this.isSizeValid = false;
	}
	
	public int getSize() {
		if (this.isSizeValid) {
			return this.size;
		}
		
		try (TypedOutputStream stream = new TypedOutputStream()) {
			this.serialize(stream);
			
			this.size = stream.toByteArray().length;
			this.isSizeValid = true;
			
			return size;
		} catch (Exception exc) {
			throw new IllegalStateException("There was an error gettin the size", exc);
		}
	}
	
	protected void setSize(int size) {
		this.size = size;
		this.isSizeValid = true;
	}
}
