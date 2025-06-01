package org.art.db.common;

import java.io.Closeable;
import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LightWeightCache<TKey, TValue> implements Closeable {
	
	@SuppressWarnings("unused")
	private static final Logger logger = LoggerFactory.getLogger(LightWeightCache.class);
	
	private final long timeout;
	private final ConcurrentMap<TKey, Entry> entries;
	private ScheduledFuture<?> timer;
	
	
	public LightWeightCache(int size, Duration timeout) {
		this.timeout = timeout.toMillis();
		this.entries = new ConcurrentHashMap<>(size);
		this.timer = Scheduler.DEFAULT.scheduleAtFixedRate(this::timer, timeout.toSeconds(), timeout.toSeconds(), TimeUnit.SECONDS);
	}
	
	public Map<TKey, TValue> asMap() {
		 Map<TKey, TValue> result = new HashMap<>();
		 
		 for (Map.Entry<TKey, Entry> entry : this.entries.entrySet()) {
			 if (entry.getValue().timestamp + timeout >= System.currentTimeMillis()) {
				 result.put(entry.getKey(), entry.getValue().value);
			 }
		 }
		 
		 return result;
	}
	
	public void clear() {
		this.entries.clear();
	}
	
	@Override
	public void close() throws IOException {
		if (this.timer != null) {
			this.timer.cancel(true);
			this.timer = null;
		}
	}
	public TValue get(TKey key) {
		Entry entry  = this.entries.get(key);

		if (entry != null && entry.timestamp + timeout < System.currentTimeMillis()) {
			this.entries.remove(key);
			entry = null;
		}
				
		return entry == null ? null : entry.value;
	}
	
	public void put(TKey key, TValue value) {
		this.entries.put(key, new Entry(value, System.currentTimeMillis()));

	}
	
	public void remove(TKey key) {
		this.entries.remove(key);
	}
	
	private void timer() {
		List<TKey> expired = new LinkedList<>();
		
		for (Map.Entry<TKey, Entry> entry : this.entries.entrySet()) {
			if (entry.getValue().timestamp + timeout < System.currentTimeMillis()) {
				expired.add(entry.getKey());
			}
		}
		
		expired.forEach(x -> this.entries.remove(x));
	}
	
	class Entry {
		private final TValue value;
		private long timestamp;
		
		
		Entry(TValue value, long timestamp) {
			this.value = value;
			this.timestamp = timestamp;
		}
	}
	
}
