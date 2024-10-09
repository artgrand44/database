package org.art.db.common;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A rolling average over N samples
 * @author artgrand
 *
 */
public class RollingAverage {
	
	@SuppressWarnings("unused")
	private static final Logger logger = LoggerFactory.getLogger(RollingAverage.class);
	
	private final ConcurrentLinkedDeque<Long>  samples = new ConcurrentLinkedDeque<>();
	private final String name;
	private AtomicLong currentSampleValue = new AtomicLong();
	private final long updateInterval;
	private long nextUpdate;
	
	private final static ConcurrentMap<String, RollingAverage> averages = new ConcurrentHashMap<>();
	
	private final static ScheduledFuture<?> poller = Scheduler.DEFAULT.scheduleAtFixedRate(() -> updateAll(), 1, 1, TimeUnit.SECONDS);
	
	
	
	private static final int MAX_SAMPLES = 100;
	
	public RollingAverage(String name) {
		this(name, 1000);
	}
	
	public RollingAverage(String name, long updateInterval) {
		
		this.name = name;
		this.updateInterval = updateInterval;
		this.nextUpdate = System.currentTimeMillis() + this.updateInterval;
		
		averages.put(name, this);
	}
	
	public synchronized void clear() {
		this.samples.clear();
		this.currentSampleValue.set(0);
	}
	
	public void logEvent() {
		this.currentSampleValue.incrementAndGet();
	}
	
	public void logEvent(long count) {
		this.currentSampleValue.addAndGet(count);
	}
	
	private static void updateAll() {
		long now = System.currentTimeMillis();
		
		for (RollingAverage average : averages.values()) {
			if (average.nextUpdate >= now) {
				long sample = average.currentSampleValue.getAndSet(0);

				average.samples.add(sample);
				average.nextUpdate = now + average.updateInterval;

				if (average.samples.size() > MAX_SAMPLES) {
					average.samples.removeFirst();
				}
			}
		}
	}
	
	
	public long getAverage() {
		if (this.samples.isEmpty()) {
			return 0;
		}
		
		long total = 0;
		
		for (Long sample : this.samples) {
			total += sample;
		}
		
		return total / this.samples.size();

	}

	public static void stop() {
		poller.cancel(true);
	}
	
	@Override
	public String toString() {
		return "RollingAverage [name=" + name + ", currentSampleValue=" + currentSampleValue + ",  samples=" + samples + "]";
	}
		
}
