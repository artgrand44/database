package org.art.db.common;

import java.io.IOException;
import java.util.Objects;


public class GUID extends  Serializable<GUID> implements Comparable<GUID> {
	private long value;
	
	public GUID() {
		this.value = GUIDGenerator.getInstance().generateValue();
	}
	
	
	
	@Override
	public int compareTo(GUID that) {
		return (int)(this.value - that.value);
	}

	@Override
	public void serialize(TypedOutputStream stream) throws IOException {
		stream.writeLong(this.value);
		
	}

	@Override
	public GUID deserialize(TypedInputStream stream) throws IOException {
		this.value = stream.readLong();
		return this;
	}

	@Override
	public int hashCode() {
		return Objects.hash(value);
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		GUID other = (GUID) obj;
		return value == other.value;
	}



	public long getValue() {
		return value;
	}



	public void setValue(long value) {
		this.value = value;
	}

}
