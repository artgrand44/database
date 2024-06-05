package org.art.db.common;

import java.io.IOException;
import java.util.Objects;


public class GUID extends  Serializable<GUID> implements Comparable<GUID> {
	
	public static final GUID MINIMUM = new GUID(0);
	
	private long value;
	
	public GUID() {
		this.value = GUIDGenerator.getInstance().generateValue();
	}
	
	private GUID(long value) {
		this.value = value;
	}
	
	@Override
	public int compareTo(GUID that) {
		long diff = this.value - that.value;
		
		if (diff == 0) {
			return 0;
		} else if (diff < 0) {
			return -1;
		} else {
			return +1;
		}
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
	public String toString() {
		return "GUID [value=" + value + "]";
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
