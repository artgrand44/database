package org.art.db.common;

import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LightWeightCache<TKey, TValue> {
	
	@SuppressWarnings("unused")
	private static final Logger logger = LoggerFactory.getLogger(LightWeightCache.class);
	
	private final long timeout;
	private final ConcurrentMap<TKey, Entry> entries;
	
	private static ConcurrentLinkedDeque<LightWeightCache<?, ?>> caches = new ConcurrentLinkedDeque<>();
	
	static {
		Scheduler.DEFAULT.scheduleAtFixedRate(() -> timer(), 1, 1, TimeUnit.MINUTES);
	}
	
	public LightWeightCache(int size, Duration timeout) {
		this.timeout = timeout.toMillis();
		this.entries = new ConcurrentHashMap<>(size);
		caches.add(this);
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
	
	private static void timer() {
		for (LightWeightCache<?, ?> cache : caches) {
			cache.timeoutEntries();
		}
	}
	
	private void timeoutEntries() {
		
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
