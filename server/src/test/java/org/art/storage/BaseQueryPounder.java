package org.art.storage;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.art.db.common.CPUMeter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
//import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class BaseQueryPounder extends BaseClientTest {
	private static final Logger logger = LoggerFactory.getLogger(BaseQueryPounder.class);

	private Deque<Throwable> errors = new ConcurrentLinkedDeque<Throwable>(); 

	
	private AtomicInteger readCount = new AtomicInteger(); 
	
	private AtomicInteger writeCount = new AtomicInteger(); 
	
	private AtomicInteger deleteCount = new AtomicInteger();
	
	private ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

	private long startTime;

	private volatile boolean stop = false;

	private final int nitems;
	private final int nthreads;
	
	Thread timer = null;

	public BaseQueryPounder(String profile, int nitems, int nthreads) {
		
		this.nitems = nitems;
		this.nthreads = nthreads;
	
		this.startInternal(profile);
	}


	@BeforeEach
	public void beforeEach() throws Exception  {
		super.beforeEach();

	}
	
	@AfterEach
	public void afterEach()  {
		this.stop();
		super.afterEach();
	}

	
	protected int getRandom(Random random) {
		double seed;
		
		do {
			seed = Math.abs(random.nextGaussian() * this.nitems);
		} while (seed > this.nitems);
			
		return (int) seed;
		
	}
	
	protected void addError(Throwable thr) {
		this.errors.add(thr);
	}

	protected void addRead() {
		this.readCount.incrementAndGet();
	}
	
	protected void addRead(int count) {
		this.readCount.addAndGet(count);
	}
	
	protected void addDelete() {
		this.deleteCount.incrementAndGet();
	}
	
	protected void addWrite() {
		this.writeCount.incrementAndGet();
	}
	protected boolean hasErrors() {
		return !this.errors.isEmpty();
	}
	
	
	@Test
	//@RepeatedTest(500)
	public void test() {
		
		try {
			this.lock.writeLock().lock();
			this.loadDabase();

			List<Thread> threads = new ArrayList<>();
			final int xloop = 0;

			for (int idx = 0; idx < this.nthreads; idx++) {
				final int threadNo = idx;
				
				Thread thread = new Thread(() -> this.pound(threadNo, xloop));
				
				thread.setName("Pound" + idx);
				threads.add(thread);
			}

			readCount.set(0);

			
			threads.forEach(x -> x.start());

			this.timer = new Thread(() -> stats());

			this.timer.setDaemon(true);
			this.timer.setName("TestTimer");
			this.timer.start();

			this.startTime = System.currentTimeMillis();
			this.lock.writeLock().unlock();
			
			for (Thread thread : threads) {
				thread.join();

				if (this.errors.size() != 0)  {
					logger.info("Test errors");
					
					for (Throwable error : this.errors) {
						logger.info(error.getMessage());
					}
				}
				
				Assertions.assertEquals(0, this.errors.size());
			}

			logger.info("end of searches " + this.getClass().getSimpleName());
			

			this.stop();

			long end = System.currentTimeMillis();
			long elapsed = end - this.startTime;
			float timePerRead = ((float)elapsed) / (float)readCount.get();


			logger.info(String.format(this.getClass().getSimpleName() + " Final Per read = %f10", timePerRead) + "ms total " + readCount.get() + " elapsed " + elapsed);
			
			/*
				try {
					Thread.sleep(1000 * 60 * 10);
				} catch (Exception exc) {

				}*/
		} catch (Exception exc) {
			logger.error(exc.getMessage());
			exc.printStackTrace();
			Assertions.fail(exc.getMessage());
			
			logger.info("exception stopping " + this.getClass().getSimpleName());
			this.stop();
		}

	}


	private void stats() {
		MemoryMXBean bean = ManagementFactory.getMemoryMXBean(); 
		int oneMeg = 1024 * 1024;
		CPUMeter cpuMeter = new CPUMeter();
		
		while (!stop) {
			long end = System.currentTimeMillis();
			long elapsed = end - this.startTime;
			float timePerRead = ((float)elapsed) / (float)readCount.get();
			long transactions = readCount.get() + writeCount.get() + deleteCount.get();
			float tps = ((float)transactions / (float)elapsed) * 1000;
			long cpu = (long)(cpuMeter.getCPU() * 100);
			
			long totalGC = 0;
		    
		    for(GarbageCollectorMXBean gc :
		    	ManagementFactory.getGarbageCollectorMXBeans()) {

		    	long count = gc.getCollectionCount();

		    	if(count >= 0) {
		    		totalGC += count;
		    	}
		    }

			if (Float.isFinite(timePerRead) && elapsed > 1000) {
				long gcRate = totalGC / (elapsed / 1000);
				
				String entry = this.getClass().getSimpleName() + " " + String.format("Per read = %f10 ms reads %s writes %s deletes %s tps %s heap %sMB gc %s/sec cpu %s", 
						timePerRead,
						readCount.get(),
						writeCount.get(),
						deleteCount.get(),
						tps,
						bean.getHeapMemoryUsage().getUsed() / oneMeg,
						gcRate,
						cpu);

				logger.info(entry);
			}

			try {
				Thread.sleep(5000);
			} catch (Exception exc) {
				logger.info("timer exception " + this.getClass().getSimpleName() + " " + exc.getMessage());
				break;
			}

		}
		
		logger.info("after timer loop " + this.getClass().getSimpleName());

	}
	
	private void stop() {
		this.stop = true;
		
		if (this.timer != null) {
			this.timer.interrupt();
			this.timer = null;
		}
	}
	
	protected abstract void pound(int index, int mainLoop); 
	
	protected abstract void loadDabase() throws Exception;
	
	protected void waitForAllStarted() {
		this.lock.readLock().lock();
	}


}
