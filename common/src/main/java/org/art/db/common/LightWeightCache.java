package org.art.db.common;

import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LightWeightCache<TKey, TValue> {
	
	@SuppressWarnings("unused")
	private static final Logger logger = LoggerFactory.getLogger(LightWeightCache.class);
	
	private final long timeout;
	private final AtomicBoolean lock = new AtomicBoolean();
	private final EntryMap entries;
	private final Function<TValue, Integer> weigher;
	private long totalWeight;
	
	
	public LightWeightCache(int size, Duration timeout) {
		this(size, timeout, x -> 1);

	}
	
	public LightWeightCache(int size, Duration timeout, Function<TValue, Integer> weigher) {
		this.timeout = timeout.toMillis();
		this.entries = new EntryMap(size, timeout.toMillis());
		this.weigher = weigher;
	}
	
	public Map<TKey, TValue> asMap() {
		 Map<TKey, TValue> result = new HashMap<>();
		 
		 this.lock();
		 
		 for (Map.Entry<TKey, Entry> entry : this.entries.entrySet()) {
			 if (entry.getValue().timestamp + timeout >= System.currentTimeMillis()) {
				 result.put(entry.getKey(), entry.getValue().value);
			 }
		 }
		 
		 this.unlock();
		 
		 return result;
	}
	
	public void clear() {
		this.lock();
		this.entries.clear();
		this.unlock();
	}
	
	public TValue get(TKey key) {
		this.lock();
		
		Entry entry  = this.entries.get(key);

		if (entry != null && entry.timestamp + timeout < System.currentTimeMillis()) {
			this.entries.remove(key);
			entry = null;
		}
				
		this.unlock();
		
		
		return entry == null ? null : entry.value;
	}
	
	private void lock() {
		while (this.lock.compareAndSet(false, true)) {
			
		}
	}
	
	public void put(TKey key, TValue value) {
		this.lock();
		
		Entry old = this.entries.get(key);
		long weight = old == null ? this.weigher.apply(value) : this.weigher.apply(value) - old.weight;
		
		this.entries.put(key, new Entry(value, System.currentTimeMillis(), weight));
		this.totalWeight += weight;
		
		this.unlock();
	}
	
	public void remove(TKey key) {
		this.lock();
		Entry entry = this.entries.remove(key);
		
		if (entry != null) {
			this.totalWeight -= entry.weight;
		}
		
		this.unlock();
	}
	
	private void unlock() {
		this.lock.set(false);
	}
	
	class Entry {
		private final TValue value;
		private final long weight;
		private long timestamp;
		
		
		Entry(TValue value, long timestamp, long weight) {
			this.value = value;
			this.timestamp = timestamp;
			this.weight = weight;
		}
	}
	
	class EntryMap extends LinkedHashMap<TKey, Entry> {
		private final int max;
		private final long timeout;
		
		private static final long serialVersionUID = 1L;
		
		EntryMap(int size, long timeout) {
			super(size, .75f, false);
			
			this.max = size;
			this.timeout = timeout;
		}
		
		@Override
		protected boolean removeEldestEntry(Map.Entry<TKey, Entry> eldest) { 
			if (totalWeight > this.max || eldest.getValue().timestamp + this.timeout < System.currentTimeMillis()) {
				totalWeight -= eldest.getValue().weight;
				return true;
			}
			
			return false;
		}
		
	}
}
