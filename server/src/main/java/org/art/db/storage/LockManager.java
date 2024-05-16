package org.art.db.storage;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.StampedLock;

import org.art.db.common.Scheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LockManager<T> {
	
	@SuppressWarnings("unused")
	private static final Logger logger = LoggerFactory.getLogger(LockManager.class);
		
	/**
	 * The next few fields are used to maintain StampedLock's for all
	 * all of the in-process nodes.
	 * 
	 * Each thread has a map from node id's to LockInfo's.  This map contains hard
	 * references for all of the locks that the thread is using and the time stamp
	 * for the corresponding lock request.
	 * 
	 * In addition, to the thread specific maps, we maintain a global map that maps
	 * node id's into soft-references to the locks.  This insure that the that the 
	 * locks are garnage collected if and only if none of the threads are using the,.
	 */
	static record LockInfo(StampedLock lock, long timestamp) {
		
	}
	
	private final ThreadLocal<Map<T, LockInfo>> threadInfo = ThreadLocal.withInitial(() -> new HashMap<>());
	
	/**
	 * The node locks for all of the currently locked nodes
	 */
	private final ConcurrentMap<T, WeakRef> locks = new ConcurrentHashMap<>();
	
	private final ReferenceQueue<StampedLock> queue = new ReferenceQueue<>();
	
	private final ScheduledFuture<?> cleaner;
	
	private final ReentrantLock lock = new ReentrantLock();
	
	public LockManager() {
		this.cleaner = Scheduler.DEFAULT.scheduleAtFixedRate(this::cleanup, 10, 10, TimeUnit.SECONDS);
	}

	public void checkReadLock(T key) {
		if (!this.isReadLocked(key)) {
			throw new IllegalStateException("Node "+ key + " is not read locked");
		}
	}
	
	public void checkWriteLock(T key) {
		if (!this.isWriteLocked(key)) {
			throw new IllegalStateException("Node "+  key + " is not write locked");
		}
	}
	
	public void checkLock(T key) {
		if (!this.isWriteLocked(key) && !isReadLocked(key)) {
			throw new IllegalStateException("Node "+  key + " is not locked");
		}
	}
	@SuppressWarnings("unchecked")
	private void cleanup() {
		WeakRef ref;
		
		while ((ref = (WeakRef)this.queue.poll()) != null) {
			if (this.locks.get(ref.key) == ref) {
				this.locks.remove(ref.key);
			}
		}
	}
	
	public String getLockId(T key) {
		StampedLock lock = this.getLock(key);
		
		return lock.toString();
	}
	
	private StampedLock getLock(T key) {
		this.lock.lock();
		
		WeakRef ref = this.locks.get(key);
		StampedLock lock; 
		
		if (ref == null || (lock = ref.get()) == null) {
			lock = new StampedLock();
			
			this.locks.put(key, new WeakRef(key, lock));
		}
		
		this.lock.unlock();
		
		return lock;
	}
	
	public boolean isReadLocked(T key) {
		WeakReference<StampedLock> ref = this.locks.get(key);
		
		if (ref == null) {
			return false;
		}
		
		StampedLock lock = ref.get();
		
		if (lock == null) {
			return false;
		}
		
		if (!lock.isReadLocked() && !lock.isWriteLocked()) {
			return false;
		}
		
		if (!threadInfo.get().containsKey(key)) {
			return false;
		}
		
		return true;
	}
	
	public boolean isWriteLocked(T key) {
		WeakReference<StampedLock> ref = this.locks.get(key);
		
		if (ref == null) {
			return false;
		}
		
		StampedLock lock = ref.get();
				
		if (lock == null || !lock.isWriteLocked() || !threadInfo.get().containsKey(key)) {
			return false;
		}
		
		return true;
	}
	
	public void unlock(T key, long stamp) {
		Map<T, LockInfo> map = threadInfo.get();
		StampedLock lock = this.getLock(key);
		
		if (lock.isWriteLocked()) {
			map.put(key, new LockInfo(lock, lock.tryConvertToReadLock(stamp)));
		} else {
			lock.unlock(stamp);
			map.remove(key);;
		}
		
	}


	public void unlock(T key) {
		this.unlock(key, threadInfo.get().get(key).timestamp);
	}
	
	public boolean unlockAll() {
		Map<T, LockInfo> map = threadInfo.get();
		boolean success = true;
		
		for (LockInfo info : map.values()) {
			long stamp = info.timestamp;
			StampedLock lock = info.lock;
			
			try {
				lock.unlock(stamp);
			} catch (IllegalMonitorStateException exc) {
				success = false;
			}			
		}
		
		map.clear();
		
		return success;		
	}

	void readLock(T key) {
		Map<T, LockInfo> map = threadInfo.get();
		
		if (!map.containsKey(key)) {
			StampedLock lock = this.getLock(key);
			map.put(key, new LockInfo(lock, lock.readLock()));
		}
	}
	
	public void stop() {
		this.cleaner.cancel(true);
	}
	
	public long writeLock(T key) throws UpgradeException {
		Map<T, LockInfo> map = threadInfo.get();
		LockInfo info = map.get(key);
		StampedLock lock = this.getLock(key);
		long stamp;
		
		if (lock.isReadLocked() && info != null) {
			stamp = lock.tryConvertToWriteLock(info.timestamp);
			
			if (stamp == 0) {
				throw new UpgradeException();
			}
		} else {
			stamp = lock.writeLock();
		}
		
		map.put(key, new LockInfo(lock, stamp));
		
		return stamp;
	}
	
	static class UpgradeException extends Exception {
		private static final long serialVersionUID = 1L;
		
	}

	class WeakRef extends WeakReference<StampedLock> {
		private final T key;
		
		WeakRef(T key, StampedLock value) {
			super(value, queue);
			
			this.key = key;
		}
	}
		

}
