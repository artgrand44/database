package org.art.storage;

import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.RepeatedTest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TreePounder extends BaseTreeTest {
	private static final Logger logger = LoggerFactory.getLogger(TreePounder.class);
	
	private Deque<Throwable> errors = new ConcurrentLinkedDeque<Throwable>(); 

	private static final int NTHREADS = 1000;
	private static final int NLOOPS = 100000;
	
	//@Test
	@RepeatedTest(2)
	public void test() {
		logger.info("Start pounder");
		
		try {
			doInsert(1000);
			List<Thread> threads = new ArrayList<>();
			
			for (int idx = 0; idx < NTHREADS; idx++) {
				final int threadNo = idx;
				threads.add(new Thread(() -> this.pound(threadNo)));
			}

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

			logger.info("end of test " + this.getClass().getSimpleName());
			
		} catch (Exception exc) {
			logger.error(exc.getMessage());
			exc.printStackTrace();
			Assertions.fail(exc.getMessage());
			
			logger.info("exception stopping " + this.getClass().getSimpleName());
		}

	}

	private void pound(int index) {
		try {
			for (int idx = 0; idx < NLOOPS; idx++) {
				for (int jdx = 0; idx < 1000; jdx++) {
					if (jdx % index == 0) {
						doSelect("ponder", new int[] { idx }, 1,  Comparison.EQ, String.valueOf(idx));
					}

					if (idx % 5 == 0) {
						int newKey = idx * 2000 + idx;

						logger.info("Inserting " + newKey);

						doInsert(newKey, newKey, 1);
						doSelect("ponder", new int[] { newKey }, 1,  Comparison.EQ, String.valueOf(newKey));
						doRemove(this.getKey(newKey), this.getValue(newKey, 0));
					}
				}
			}
		} catch (Exception exc) {
			Assertions.fail(exc.getMessage());
		}
	}
}
