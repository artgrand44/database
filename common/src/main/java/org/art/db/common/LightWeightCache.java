package org.art.db.common;

import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
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
		int weight = old == null ? this.weigher.apply(value) : this.weigher.apply(value) - old.weight;
		
		this.entries.put(key, new Entry(value, System.currentTimeMillis(), weight));
		
		this.unlock();
	}
	
	public void remove(TKey key) {
		this.lock();
		this.entries.remove(key);
		this.unlock();
	}
	
	private void unlock() {
		this.lock.set(false);
	}
	
	class Entry {
		private final TValue value;
		private final int weight;
		private long timestamp;
		
		
		Entry(TValue value, long timestamp, int weight) {
			this.value = value;
			this.timestamp = timestamp;
			this.weight = weight;
		}
	}
	
	class EntryMap extends LinkedHashMap<TKey, Entry> {
		private final int max;
		private final long timeout;
		private AtomicLong totalWeight = new AtomicLong();
		
		private static final long serialVersionUID = 1L;
		
		EntryMap(int size, long timeout) {
			super(size, .75f, false);
			
			this.max = size;
			this.timeout = timeout;
		}
		
		@Override
		public Entry put(TKey key, Entry value) {
			Entry old = super.put(key, value);
			int weight = old == null ? value.weight : value.weight - old.weight;
			
			this.totalWeight.addAndGet(weight);
			
			return old;			
		}
		
		@Override
		public Entry remove(Object key) {
			Entry old = super.remove(key);
			
			if (old != null) {
				this.totalWeight.addAndGet(-old.weight);
			}
			
			return old;
		}
		
		@Override
		protected boolean removeEldestEntry(Map.Entry<TKey, Entry> eldest) { 
			if (this.totalWeight.get() > this.max || eldest.getValue().timestamp + this.timeout < System.currentTimeMillis()) {
				this.totalWeight.addAndGet(-eldest.getValue().weight);
				return true;
			}
			
			return false;
		}
		
	}
}
