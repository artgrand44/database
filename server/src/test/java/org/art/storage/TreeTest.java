package org.art.storage;

import java.io.ByteArrayOutputStream;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.art.db.common.Comparison;
import org.art.db.common.Key;
import org.art.db.common.TypedInputStream;
import org.art.db.common.TypedOutputStream;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TreeTest extends BaseTreeTest {
	
	private static final Logger logger = LoggerFactory.getLogger(TreeTest.class);
	
	@Test
	public void basicTest() {
		logger.info("Start basic");

		try {
			doInsert(3);
						
			for (int idx = 0; idx < 3; idx++) {
				doSelect("search", 
						new int[] { idx },
						1,
						Comparison.EQ, String.valueOf(idx));
			}

			doSelect("basic GE",
					 new int[] { 0, 1, 2 },
					 1,
					 Comparison.GE, "0");
			
			doSelect("basic GT",
					 new int[] { 1, 2 },
					 1,
					 Comparison.GT, "0");
			
			doSelect("basic LE",
					 new int[] { 0, 1, 2 },
					 1,
					 Comparison.LE, "2");
			
			doSelect("basic LT",
					 new int[] { 0, 1 },
					 1,
					 Comparison.LT, "2");
			
			doSelect("basic NE",
					 new int[] { 0, 2 },
					 1,
					 Comparison.NE, "1");
			
		} catch (AssertionFailedError err) {
			throw err;
		} catch(Exception exc) {
			exc.printStackTrace();
			Assertions.fail(exc.getMessage());
		}
	}
	
	//@Test
	//@RepeatedTest(3)
	public void bigCrashTest() {
		logger.info("Start bigCrash");

		
		try {
			Random random = new Random();
			
			int nextCrash = random.nextInt(20);
			int nextFlush = random.nextInt(25);
			int lastCrash = 0;
			
			Set<Integer> addedSinceCrash = new HashSet<>();
			
			for (int idx = 0; idx < 25000; idx++) {
				
				if (idx % 1000 == 0) {
					logger.info("Process " + idx);
				}
				this.doInsert("Key" + idx, "Value" + idx);
				
				addedSinceCrash.add(idx);
				
				if (idx % 20 == nextCrash) {
					lastCrash = idx;
					addedSinceCrash.clear();
					doCrash();
					doOpen();
					nextCrash = random.nextInt(20);
				} else if (idx % 25 == nextFlush) {
					//doFlush();
					
					for (Integer added : addedSinceCrash) {
						Set<String> result = this.tree.getAll(Comparison.EQ, new Key("Key" + added));
						
						Assertions.assertThat(result.size() == 1).isTrue();
						Assertions.assertThat(result.iterator().next().equals("Value" + added)).isTrue();
					}
					
					doClose();
					doOpen();
					
					for (Integer added : addedSinceCrash) {
						Set<String> result = this.tree.getAll(Comparison.EQ, new Key("Key" + added));
						
						if (result.size() != 1) {
							logger.info("size is " + result.size() + " added = " + added + " last " + lastCrash + " idx " + idx);
							result = this.tree.getAll(Comparison.EQ, new Key("Key" + added));
							logger.info("after " + result);
							
						}
						
						Assertions.assertThat(result.size() == 1).isTrue();
						Assertions.assertThat(result.iterator().next().equals("Value" + added)).isTrue();
					}
					
					nextFlush = random.nextInt(25);
				}
			}
		} catch (AssertionFailedError err) {
			throw err;
		} catch(Exception exc) {
			exc.printStackTrace();
			Assertions.fail(exc.getMessage());
		}
	}

	@Test
	public void crashTest() {
		logger.info("Start crash");

		try {
			doInsert(0, 3, 1);

			for (int idx = 0; idx < 3; idx++) {
				doSelect("search", 
						new int[] { idx },
						1,
						Comparison.EQ, String.valueOf(idx));
			}

			doFlush();
			
			doInsert(4, 6, 1);
			doCrash();
			doOpen();

			for (int idx = 0; idx < 3; idx++) {
				doSelect("after open", 
						new int[] { idx },
						1,
						Comparison.EQ, String.valueOf(idx));
			}
			
			
			doInsert(10, 1000, 1);
			doCrash();
			doOpen();
			
			doInsert(2000, 3000, 1);
			
		} catch (AssertionFailedError err) {
			throw err;
		} catch(Exception exc) {
			exc.printStackTrace();
			Assertions.fail(exc.getMessage());
		}
	}

	@Test
	public void edgeTest() {
		logger.info("Start edge");

		try {
			doInsert(5, 10, 1);
			
			
			doSelect("edge GE0",
					 new int[] {5, 6, 7, 8, 9, 10 },
					 1,
					 Comparison.GE, "0");
			
			doSelect("edge GE7",
					 new int[] {7, 8, 9, 10 },
					 1,
					 Comparison.GE, "7");
			
			doSelect("edge GE11",
					 new int[] { },
					 1,
					 Comparison.GE, "11");
			
			doSelect("edge GT0",
					 new int[] { 5, 6, 7, 8, 9, 10 },
					 1,
					 Comparison.GT, "0");
			
			doSelect("edge GT7",
					 new int[] { 8, 9, 10 },
					 1,
					 Comparison.GT, "7");
			
			doSelect("edge GT10",
					 new int[] {  },
					 1,
					 Comparison.GT, "10");
			
			doSelect("edge LE2",
					 new int[] {  },
					 1,
					 Comparison.LE, "2");
			
			doSelect("edge LE5",
					 new int[] { 5 },
					 1,
					 Comparison.LE, "5");
			
			doSelect("edge LE2",
					 new int[] { },
					 1,
					 Comparison.LE, "2");
			
			doSelect("edge LT7",
					 new int[] { 5, 6 },
					 1,
					 Comparison.LT, "7");
			
			doSelect("edge LT5",
					 new int[] {  },
					 1,
					 Comparison.LT, "5");
		
		} catch (AssertionFailedError err) {
			throw err;
		} catch(Exception exc) {
			exc.printStackTrace();
			Assertions.fail(exc.getMessage());
		}
	}
	
	@Test
	public void multiValueTest() {
		logger.info("Start edge");

		try {
			doInsert(5, 10, 4);
			
			
			doSelect("multi GE0",
					 new int[] {5, 6, 7, 8, 9, 10 },
					 4,
					 Comparison.GE, "0");
			
			doSelect("multi GE7",
					 new int[] {7, 8, 9, 10 },
					 4,
					 Comparison.GE, "7");
			
			doSelect("multi GE11",
					 new int[] { },
					 4,
					 Comparison.GE, "11");
			
			doSelect("multi GT0",
					 new int[] { 5, 6, 7, 8, 9, 10 },
					 4,
					 Comparison.GT, "0");
			
			doSelect("multi GT7",
					 new int[] { 8, 9, 10 },
					 4,
					 Comparison.GT, "7");
			
			doSelect("multi GT10",
					 new int[] {  },
					 4,
					 Comparison.GT, "10");
			
			doSelect("multi LE2",
					 new int[] {  },
					 4,
					 Comparison.LE, "2");
			
			doSelect("multi LE5",
					 new int[] { 5 },
					 4,
					 Comparison.LE, "5");
			
			doSelect("multi LE2",
					 new int[] { },
					 4,
					 Comparison.LE, "2");
			
			doSelect("multi LT7",
					 new int[] { 5, 6 },
					 4,
					 Comparison.LT, "7");
			
			doSelect("multi LT5",
					 new int[] {  },
					 4,
					 Comparison.LT, "5");
		
		} catch (AssertionFailedError err) {
			throw err;
		} catch(Exception exc) {
			exc.printStackTrace();
			Assertions.fail(exc.getMessage());
		}

		
	}

	@Test
	public void insertTest() throws Exception {
		Set<Integer> keys = new HashSet<>();
		
		for (int idx = 0; idx < 1000; idx++) {
			doInsert(idx, idx, 1);
			
			keys.add(idx);
		
			if (idx % 100 == 0) {
				for (Integer key : keys) {
					doSelect("insert1",
							 new int[] { key },
							 1,
							 Comparison.EQ, key.toString());
				}
			}
		}
		
		keys.clear();
		this.doClear();
		
		for (int idx = 1000; idx <= 0; idx--) {
			doInsert(idx, idx, 1);
			
			keys.add(idx);
			
			if (idx % 100 == 0) {
				for (Integer key : keys) {
					doSelect("insert1",
							 new int[] { key },
							 1,
							 Comparison.EQ, key.toString());
				}
			}
		}
		
		
		keys.clear();
		this.doClear();
		
		for (int idx = 0; idx < 500; idx++) {
			doInsert(idx, idx, 1);
			
			keys.add(idx);
			
			if (idx % 100 == 0) {
				for (Integer key : keys) {
					doSelect("insert1",
							 new int[] { key },
							 1,
							 Comparison.EQ, key.toString());
				}
			}
		}
		
		for (int idx = 1000; idx <= 500; idx--) {
			doInsert(idx, idx, 1);
			
			keys.add(idx);
			
			if (idx % 100 == 0) {
				for (Integer key : keys) {
					doSelect("insert1",
							 new int[] { key },
							 1,
							 Comparison.EQ, key.toString());
				}
			}
		}
	}
	
	@Test
	public void iteratorTest() {
		logger.info("Start IteratorTest");

		try {
			Iterator<Map.Entry<Key, String>> iterator = tree.iterator();
			
			Assertions.assertThat(!iterator.hasNext()).isTrue();
			
			for (int idx = 0; idx < 500; idx++) {
				String key = "Key" + StringUtils.leftPad(String.valueOf(idx), 4);
				this.doInsert(key, key);
			}

			iterator = tree.iterator();
			
			int counter = 0;
			
			while (iterator.hasNext()) {
				Map.Entry<Key, String> entry = iterator.next();
				
				String key = "Key" + StringUtils.leftPad(String.valueOf(counter), 4);
				
				Assertions.assertThat(entry.getValue().equals(key)).isTrue();
				counter++;
			}
			
			Assertions.assertThat(counter == 500).isTrue();
		} catch (AssertionFailedError err) {
			throw err;
		} catch(Exception exc) {
			exc.printStackTrace();
			Assertions.fail(exc.getMessage());
		}
	}

	@Test
	public void logDirCrashTest() {
		logger.info("Start crash");

		
		try {
			this.doClear();
			
			doInsert(0, 3, 1);

			for (int idx = 0; idx < 3; idx++) {
				doSelect("search", 
						new int[] { idx },
						1,
						Comparison.EQ, String.valueOf(idx));
			}

			doFlush();
			
			doInsert(4, 6, 1);
			doCrash();
			doOpen();

			for (int idx = 0; idx < 3; idx++) {
				doSelect("after open", 
						new int[] { idx },
						1,
						Comparison.EQ, String.valueOf(idx));
			}
			
			
			doInsert(10, 1000, 1);
			doCrash();
			doOpen();
			
			doInsert(2000, 3000, 1);
			
		} catch (AssertionFailedError err) {
			throw err;
		} catch(Exception exc) {
			exc.printStackTrace();
			Assertions.fail(exc.getMessage());
		} 
	}


	

	@Test
	public void openAndCloseTest() {
		logger.info("Start open and close");

		try {
			Key key = new Key("123");
			byte[] bytes;
			
			try (ByteArrayOutputStream bstream = new ByteArrayOutputStream(); TypedOutputStream stream = new TypedOutputStream(bstream)) {
				stream.writeObject(key);
				
				stream.flush();
				bstream.flush();
				
				bytes = bstream.toByteArray();
			}
			
			try (TypedInputStream stream = new TypedInputStream(bytes)) {
				Key key2 = (Key)stream.readObject();
				
				Assertions.assertThat(key.equals(key2)).isTrue();
			}
			
			doInsert(3);

			for (int idx = 0; idx < 3; idx++) {
				doSelect("search", 
						new int[] { idx },
						1,
						Comparison.EQ, String.valueOf(idx));
			}

			doClose();
			doOpen();

			for (int idx = 0; idx < 3; idx++) {
				doSelect("after open", 
						new int[] { idx },
						1,
						Comparison.EQ, String.valueOf(idx));
			}

		} catch (AssertionFailedError err) {
			throw err;
		} catch(Exception exc) {
			exc.printStackTrace();
			Assertions.fail(exc.getMessage());
		}
	}


	@Test
	public void rangeTest() {
		logger.info("Start range");

		try {
			doInsert(10);
			
			doSelectRange("range all",
					      new int[] { 0, 1, 2, 3, 4, 5, 6, 7, 8, 9 },
					      1,
					 	  "0", "9");
			
			doSelectRange("range 3-5",
				      new int[] {3, 4, 5 },
				      1,
				 	  "3", "5");
			
			doSelectRange("range 8-20",
				      new int[] {8, 9 },
				      1,
				 	  "8", "20");
			
			doRemove(getKey("0"), getValue(0, 0));
			
			doSelectRange("range 0-3",
				      new int[] {1, 2, 3 },
				      1,
				 	  "0", "3");
		} catch (AssertionFailedError err) {
			throw err;
		} catch(Exception exc) {
			exc.printStackTrace();
		}
	}
			
	@Test
	public void removeTest() {
		logger.info("Start remove");

		try {
			Set<Integer> keys = new HashSet<>();
			
			for (int idx = 0; idx < 1000; idx++) {
				doInsert(idx, idx, 1);
				
				keys.add(idx);
			
				if (idx % 100 == 0) {
					String key = getKey(idx);
					
					doSelect("before",
							new int[] { idx },
							1,
							Comparison.EQ, String.valueOf(idx));


					doRemove(key, getValue(idx, 0));
					
					doSelect("after",
							 new int[] {  },
							 1,
							 Comparison.EQ, String.valueOf(idx));
				}
			}
			
		} catch (AssertionFailedError err) {
			throw err;
		} catch(Exception exc) {
			exc.printStackTrace();
			Assertions.fail(exc.getMessage());
		}
	}


}
