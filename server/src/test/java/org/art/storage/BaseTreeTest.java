package org.art.storage;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.commons.lang3.StringUtils;
import org.art.db.common.Comparison;
import org.art.db.common.Key;
import org.art.db.storage.Tree;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class BaseTreeTest extends BaseTest {
	
	private static final Logger logger = LoggerFactory.getLogger(BaseTreeTest.class);
	
	protected Tree<String> tree;
	
	private AtomicLong searchCount = new AtomicLong();
	
	private AtomicLong insertCount = new AtomicLong();
	
	private long startTime;
	
	protected File BTREE = new File("./test/btree.dat");
	
	@BeforeEach
	public void beforeEach() throws Exception {
		super.beforeEach();
		File[] files = new File("./test").listFiles();
		
		if (files != null) {
			for (File file : files) {
				file.delete();
			}
		}
		
		File file = new File("/Users/artgrand/repros/storage/./test/btree_dat.log");
		
		file.delete();
		
		this.doClear();
		this.searchCount.set(0);
		this.searchCount.set(0);
		this.startTime = System.currentTimeMillis();
	}
	
	@AfterEach
	public void afterEach()  {
		super.afterEach();
		
		File file = new File("/Users/artgrand/repros/storage/./test/btree_dat.log");
		
		if (file.exists()) {
			logger.info("log file " + file.getAbsolutePath() + " not deleted");
		}
		
		long endTime = System.currentTimeMillis();
		long elapsed = endTime - this.startTime;
		long searchCount = this.searchCount.get();
		long timePerSearch = searchCount == 0 ? 0 : elapsed / searchCount;
		
		logger.info("Elapsed: " + elapsed + " Search Time: " + timePerSearch + " Searches: " + searchCount + " Inserts:" + this.insertCount.get() 
		            + " Reads: " + this.tree.getReadCount() + " Writes: " + this.tree.getWriteCount());
		
		try {
			this.tree.close();
		} catch (Exception exc) {
			throw new IllegalStateException(exc);
		}
		
	}
	
	protected void doClear() throws Exception {
		BTREE.mkdirs();
		BTREE.delete();
			
		this.tree = new Tree<>(BTREE, String.class);
	}
	
	protected void doDelete() {
		BTREE.delete();
	}
	
	protected void doClose() throws Exception {
		this.tree.close();
	}
	
	protected void doCrash() throws Exception {
		this.tree.crash();
	}
	
	protected void doFlush() throws Exception {
		this.tree.flushAll();
	}
	
	protected void doOpen() throws Exception {
		this.tree = new Tree<>(BTREE, String.class);
	}
	
	protected void doOpenInMemory() throws Exception {
		this.tree = new Tree<>(String.class);
		this.tree = new Tree<>(String.class);
	}
	
	
	protected void doInsert(String key, String value) throws Exception {
		this.insertCount.incrementAndGet();
		tree.put(new Key(key, value), value);
	}
	
	protected void doInsert(int nvalues) throws Exception {
		for (int idx = 0; idx < nvalues; idx++) {
			this.doInsert(getKey(idx), getValue(idx, 0));
		}
	}
	
	protected void doInsert(int keyStart, int keyEnd, int nvalues) throws Exception {
		for (int idx = keyStart; idx <= keyEnd; idx++) {
			String key = getKey(idx);
			
			for (int jdx = 0; jdx < nvalues; jdx++) {
				String value = getValue(idx, jdx);
			
				this.doInsert(key, value);
			}
		}
	}
	
	
	protected void doSelect(String title, int[] expected, int nvalues, Comparison comparison, String key) throws Exception {
		this.searchCount.incrementAndGet();
		
		List<String> expectedValues = new ArrayList<>();
		List<String> actualValues = new ArrayList<>();
		String fullKey = getKey(key);
		List<String> result = this.tree.getAll(comparison, new Key(fullKey));
		
		for (int exp : expected) {
			for (int idx = 0; idx < nvalues; idx++) {
				expectedValues.add(getValue(exp, idx));
			}
		}
		
		for (String str : result) {
			actualValues.add(str);
		}
		
		Collections.sort(actualValues);
		Collections.sort(expectedValues);
		
		if (!actualValues.equals(expectedValues)) {
			logger.info("Bad match " + title);
			logger.info("Key       " + fullKey);
			logger.info("expected  " + expectedValues);
			logger.info("actual    " + actualValues);
			
			Assertions.fail(title);
		}		
	}

	protected void doSelectRange(String title, int[] expected, int nvalues, String first, String last) throws Exception {
		this.searchCount.incrementAndGet();
		
		List<String> expectedValues = new ArrayList<>();
		List<String> actualValues = new ArrayList<>();
		List<String> result = this.tree.getAllBetween(new Key(getKey(first)), new Key(getKey(last)));
		
		for (int exp : expected) {
			for (int idx = 0; idx < nvalues; idx++) {
				expectedValues.add(getValue(exp, idx));
			}
		}
		
		for (String str : result) {
			actualValues.add(str);
		}
		
		Collections.sort(actualValues);
		Collections.sort(expectedValues);
		
		if (!actualValues.equals(expectedValues)) {
			logger.info("Bad match " + title);
			logger.info("First     " + first);
			logger.info("Last      " + last);
			logger.info("expected  " + expectedValues);
			logger.info("actual    " + actualValues);
			
			Assertions.fail(title);
		}		
	}

	protected void doRemove(String key, String value) throws Exception {
		this.tree.remove(new Key(key, value));
	}
	
	protected String getKey(int idx) {
		return getKey(String.valueOf(idx));
	}
	
	protected String getKey(String str) {
		return "Key" + StringUtils.leftPad(str, 2, "0");
	}
	
	protected String getValue(int keyIndex, int valueIndex) {
		return "Value." + keyIndex + "." + valueIndex;
	}
	
}
