package org.art.db.common;

import java.io.Closeable;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LightWeightCache<TKey, TValue> implements Closeable {
	
	@SuppressWarnings("unused")
	private static final Logger logger = LoggerFactory.getLogger(LightWeightCache.class);
	
	private final long timeout;
	 
	private final List<Segment> segments = new ArrayList<>();
	private final AtomicInteger referenceCount = new AtomicInteger();
	
	public LightWeightCache(int size, Duration timeout) {
		this.timeout = timeout.toMillis();
		
		for (int idx = 0; idx < 4; idx++) {
			this.segments.add(new Segment(size / 4, this.timeout));
		}
	}
	
	public void addReference() {
		this.referenceCount.incrementAndGet();
	}
	
	public Map<TKey, TValue> asMap() {
		 Map<TKey, TValue> result = new HashMap<>();
		 
		 for (Segment segment : this.segments) {
			 result.putAll(segment.getValueMap());
		 }
		 
		 return result;
	}
	
	public void clear() {
		for (Segment segment : this.segments) {
			 segment.clear();
		 }
	}
	

	@Override
	public void close() throws IOException {
		this.clear();
	}
	
	private Segment getSegment(TKey key) {
		return this.segments.get(key.hashCode() & 0x03);
	}
	
	public TValue get(TKey key) {
		Segment segment = this.getSegment(key);
		Entry entry = segment.get(key);
		
		if (entry != null && entry.timestamp + timeout < System.currentTimeMillis()) {
			segment.remove(key);
			entry = null;
		}
	
		return entry == null ? null : entry.value;
	}
	
	public void put(TKey key, TValue value) {
		Segment segment = this.getSegment(key);
		
		segment.put(key, new Entry(value, System.currentTimeMillis()));
	}
	
	public void remove(TKey key) {
		Segment segment = this.getSegment(key);
		
		segment.remove(key);
	}
	
	public int removeReference() {
		return this.referenceCount.decrementAndGet();
	}
	class Entry {
		private TValue value;
		private long timestamp;
		
		Entry(TValue value, long timestamp) {
			this.value = value;
			this.timestamp = timestamp;
		}
	}
	
	class Segment extends LinkedHashMap<TKey, Entry> {
		private final int max;
		private final long timeout;
		private final AtomicBoolean lock = new AtomicBoolean();
		
		private static final long serialVersionUID = 1L;
		
		Segment(int size, long timeout) {
			super(size, .75f, false);
			
			this.max = size;
			this.timeout = timeout;
		}
		
		@Override
		public void clear() {
			this.lock();
			super.clear();
			this.unlock();
		}
		
		@Override
		public Entry get(Object key) {
			Entry entry;
			
			this.lock();
			entry = super.get(key);
			this.unlock();
			
			return entry;
		}
		
		Map<TKey, TValue> getValueMap() {
			Map<TKey, TValue> result = new HashMap<>();
			 
			 this.lock();
			 
			 for (Map.Entry<TKey, Entry> entry : this.entrySet()) {
				 if (entry.getValue().timestamp + timeout >= System.currentTimeMillis()) {
					 result.put(entry.getKey(), entry.getValue().value);
				 }
			 }
			 
			 this.unlock();
			 
			 return result;
		}
		
		private void lock() {
			while (this.lock.compareAndSet(false, true)) {
				Thread.onSpinWait();
			}
		}
			
		private void unlock() {
			this.lock.set(false);
		}
		
		@Override
		public  Entry put(TKey key, Entry entry) {
			 Entry old;
			
			this.lock();
			old = super.put(key, entry);
			this.unlock();
			
			return old;
		}
		
		@Override
		public Entry remove(Object key) {
			Entry entry;
			
			this.lock();
			entry = super.remove(key);
			this.unlock();
			
			return entry;
		}
		
		@Override
		protected boolean removeEldestEntry(Map.Entry<TKey, Entry> eldest) { 
			return this.size() > this.max || eldest.getValue().timestamp + this.timeout < System.currentTimeMillis();
		}
		
	}

}
