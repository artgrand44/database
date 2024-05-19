package org.art.db.storage;

import java.util.concurrent.TimeUnit;

import org.art.db.common.RollingAverage;
import org.art.db.common.Scheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A utility class for thread-specific tracing
 */
public class Trace {
	private static final Logger logger = LoggerFactory.getLogger(Trace.class);
	
	private static ThreadLocal<StringBuilder> traces = ThreadLocal.withInitial(() -> new StringBuilder());
	
	public final static RollingAverage keyLocks = new RollingAverage("KeyLocks");
	
	public final static RollingAverage nodeLocks = new RollingAverage("NodeLocks");
	
	public final static RollingAverage nodeReads = new RollingAverage("NodeLocks");
	
	public final static RollingAverage nodeWrites = new RollingAverage("NodeLocks");
	
	public final static RollingAverage lockManagerLocks = new RollingAverage("LockManager");
	
	public final static RollingAverage memoryLocks = new RollingAverage("MemoryLocks");
	
	public final static RollingAverage commitLocks = new RollingAverage("CommitLocks");
	
	public final static RollingAverage allocateLocks = new RollingAverage("AllocateLocks");
	
	public final static RollingAverage tableLocks = new RollingAverage("TableLocks");
	
	static {
		//Scheduler.DEFAULT.scheduleAtFixedRate(() -> logStats(), 10, 10, TimeUnit.SECONDS);
	}
	
	public static String asString() {
		return traces.get().toString();
	}
	
	public static void clear() {
		traces.get().setLength(0);
	}
	
	public static void log(String entry) {
		traces.get().append(entry).append("\n");
	}

	private static void logStats() {
		StringBuilder builder = new StringBuilder();
		
		builder.append("Keys = "        + keyLocks.getAverage() + " ");
		builder.append("Nodes = "       + nodeLocks.getAverage() + " ");
		builder.append("Reads = "       + nodeReads.getAverage() + " ");
		builder.append("Writes = "      + nodeWrites.getAverage() + " ");
		builder.append("LockManager = " + lockManagerLocks.getAverage() + " ");
		builder.append("Memory = "      + memoryLocks.getAverage() + " ");
		builder.append("Commits = "     + commitLocks.getAverage() + " ");
		builder.append("Allocate = "    + allocateLocks.getAverage() + " ");
		builder.append("Tables = "      + tableLocks.getAverage() + " ");
		
		logger.info(builder.toString());
	}
}
