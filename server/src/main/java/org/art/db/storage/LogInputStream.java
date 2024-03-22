package org.art.db.storage;

import java.io.DataInputStream;
import java.io.InputStream;

import org.art.db.common.TypedInputStream;

public class LogInputStream  implements AutoCloseable {
	private DataInputStream stream;
	
	public LogInputStream(InputStream stream) {
		this.stream = new DataInputStream(stream);
	}
	
	@Override
	public void close() throws Exception {
		this.stream.close();		
	}
	
	public Object readObject() {
		try {
			if (this.stream.readByte() != 1) {
				return null;
			}
			
			byte[] bytes = new byte[this.stream.readInt()];
			
			this.stream.read(bytes);
			
			try (TypedInputStream astream = new TypedInputStream(bytes)) {
				return astream.readObject();
			}
		} catch (Exception exc) {
			return null;
		}		
	}

	
	
}
