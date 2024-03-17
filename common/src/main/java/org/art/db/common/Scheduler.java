package org.art.db.common;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

public class Scheduler {
	
	public static ScheduledExecutorService createScheduler(String name, int count) {
		return Executors.newScheduledThreadPool(count, new SchedulerThreadFactory(name));
	}
			
	public static final ScheduledExecutorService DEFAULT = createScheduler("Default", 30);
	
	static class SchedulerThreadFactory implements ThreadFactory {
		String prefix;
		AtomicInteger count = new AtomicInteger();
		
		SchedulerThreadFactory(String prefix) {
			this.prefix = prefix;
		}

		@Override
		public Thread newThread(Runnable runnable) {
			Thread thread = new Thread(runnable);
			
			thread.setDaemon(true);
			thread.setName(this.prefix + this.count.incrementAndGet());
			
			return thread;
		}
		
	}
}
