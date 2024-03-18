package org.art.db.storage;

/**
 * A utility class for thread-specific tracing
 */
public class Trace {
	private static ThreadLocal<StringBuilder> traces = ThreadLocal.withInitial(() -> new StringBuilder());
	
	public static String asString() {
		return traces.get().toString();
	}
	
	public static void clear() {
		traces.get().setLength(0);
	}
	
	public static void log(String entry) {
		traces.get().append(entry).append("\n");
	}
		
}
