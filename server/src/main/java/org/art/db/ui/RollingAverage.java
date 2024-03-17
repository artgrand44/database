package org.art.db.ui;

import java.util.LinkedList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.art.db.common.Scheduler;
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
	
	private final LinkedList<Long>  samples = new LinkedList<>();
	private final String name;
	private AtomicLong currentSampleValue = new AtomicLong();
	
	private static final int MAX_SAMPLES = 100;
	
	RollingAverage(String name) {
		this.name = name;
		Scheduler.DEFAULT.scheduleAtFixedRate(this::update, 1, 1, TimeUnit.SECONDS);
	}
	
	public synchronized void clear() {
		this.samples.clear();
		this.currentSampleValue.set(0);
	}
	
	void logEvent() {
		this.currentSampleValue.incrementAndGet();
	}
	
	void logEvent(long count) {
		this.currentSampleValue.addAndGet(count);
	}
	
	synchronized private void update() {
		long sample = this.currentSampleValue.getAndSet(0);
		
		this.samples.add(sample);

		if (this.samples.size() > MAX_SAMPLES) {
			this.samples.removeFirst();
		}
	}
	
	synchronized long getAverage() {
		if (this.samples.isEmpty()) {
			return 0;
		}
		
		long total = 0;
		
		for (Long sample : this.samples) {
			total += sample;
		}
		
		return total / this.samples.size();

	}

	@Override
	public String toString() {
		return "RollingAverage [name=" + name + ", currentSampleValue=" + currentSampleValue + ",  samples=" + samples + "]";
	}
		
}
