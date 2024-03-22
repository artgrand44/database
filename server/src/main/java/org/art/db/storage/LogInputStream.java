package org.art.db.storage;

import java.io.InputStream;

import org.art.db.common.LogEntry;
import org.art.db.common.TypedInputStream;

/**
 * A stream for reading log files. It assumes that any invalid field
 * types detected by TypedInputStream indicate the end of the log.
 */
public class LogInputStream  implements AutoCloseable {
	private TypedInputStream stream;
	
	public LogInputStream(InputStream stream) {
		this.stream = new TypedInputStream(stream);
	}
	
	@Override
	public void close() throws Exception {
		this.stream.close();		
	}
	
	public LogEntry read() {
		try {
			return (LogEntry)this.stream.readObject();
		} catch (Exception exc) {
			return null;
		}		
	}	
	
}
