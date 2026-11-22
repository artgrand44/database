package org.art.db.common;

import java.io.IOException;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class GUID implements Comparable<GUID> {
	
	@SuppressWarnings("unused")
	private static final Logger logger = LoggerFactory.getLogger(GUID.class);
	
	public static final GUID MINIMUM = new GUID(0);
	
	private long time;
	private long counter;
	private byte[] address;
	
	private static final byte[] localAddress;
	private static final AtomicLong next = new AtomicLong();
	
	static {
		try {
			localAddress =  InetAddress.getLocalHost().getAddress();
		} catch (Exception exc) {
			throw new IllegalStateException(exc);
		}
	}
	
	public GUID() {
		this.time = System.currentTimeMillis();
		this.counter = next.incrementAndGet();
		this.address = localAddress;

	} 
	
	private GUID(long value) {
		this.time = 0;
		this.counter = 0;
		this.address = new byte[4];
	}
	
	@Override
	public int compareTo(GUID that) {
		if (this.time != that.time) {
			return (int)(this.time - that.time);
		}
		
		if (this.counter != that.counter) {
			return (int)(this.counter - that.counter);
		}
		
		return Arrays.compare(this.address, that.address);
	}

	public void serialize(TypedOutputStream stream) throws IOException {
		stream.writeLong(this.time);
		stream.writeLong(this.counter);
		stream.writeInt(this.address.length);
		stream.write(this.address);
		
	}

	public GUID deserialize(TypedInputStream stream) throws IOException {
		this.time = stream.readLong();
		this.counter = stream.readLong();
		this.address = new byte[stream.readInt()];
		
		stream.read(this.address);
		
		return this;
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + Arrays.hashCode(address);
		result = prime * result + Objects.hash(counter, time);
		return result;
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
		return Arrays.equals(address, other.address) && counter == other.counter && time == other.time;
	}

	@Override
	public String toString() {
		return "GUID [time=" + time + ", counter=" + counter + ", address=" + Arrays.toString(address) + "]";
	}

	


}
