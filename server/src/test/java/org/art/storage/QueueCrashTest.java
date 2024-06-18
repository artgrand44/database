package org.art.storage;


import java.io.File;
import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

import org.apache.commons.io.FileUtils;
import org.art.db.common.Configuration;
import org.art.db.common.Document;
import org.art.db.common.DocumentSet;
import org.art.db.common.GUID;
import org.art.db.common.Key;
import org.art.db.storage.ColumnInfo;
import org.art.db.storage.Metadata;
import org.art.db.storage.TableManager;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.opentest4j.AssertionFailedError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class QueueCrashTest  {
	
	private static final Logger logger = LoggerFactory.getLogger(QueueCrashTest.class);
	
	private Document createDocument(int idx) throws Exception {
		Document doc = new Document();
		
		doc.put("id", new GUID());
		doc.put("name", "name" + idx);
		doc.put("value", "value" + idx);
		
		return doc;
	}
	
	@BeforeEach
	public void beforeEach() throws Exception {
		File[] files = new File("./test").listFiles();
		
		if (files != null) {
			for (File file : files) {
				file.delete();
			}
		}
		
	}
	
	
	//@Test
	@RepeatedTest(50)
	public void test() {
		try {
			File directory = new File("./atest");
			File file = new File(directory, "test.log");
			
			file.mkdirs();
			file.delete();
			
			FileUtils.deleteDirectory(new File("./dbroot/test"));
			
			new File("./dbroot/test").mkdirs();
			
			Configuration.getInstance().load("./tinybatch.properties");
			
			ColumnInfo nameInfo = new ColumnInfo(String.class, false, false, "");
			ColumnInfo valueInfo = new ColumnInfo(String.class, false, false, "");
			ColumnInfo idInfo = new ColumnInfo(GUID.class, true, false, null);
			SortedMap<String, ColumnInfo> columns = new TreeMap<>();
			
			columns.put("name", nameInfo);
			columns.put("value", valueInfo);
			columns.put("id", idInfo);
			
			Metadata metadata = new Metadata("test", Collections.singletonList("id"), columns, Collections.emptyList(), true);
			
			Metadata.setMetadata(metadata);
			
			TableManager manager = new TableManager("test");
			
			Random random = new Random();
			int nextCrash = random.nextInt(200);
			
			Set<GUID> deleted = new HashSet<>();
			Set<GUID> inserted = new HashSet<>();
			
			for (int idx = 0; idx < 250; idx++) {
				Document doc1 = createDocument(idx);
				
				inserted.add(doc1.get("id", GUID.class));
				manager.insert(doc1, true, true);
			}
			
			for (int idx = 500; idx < 1000; idx++) {
				Document doc1 = createDocument(idx);
				
				
				manager.insert(doc1, true, true);
				inserted.add(doc1.get("id", GUID.class));
				
				DocumentSet set = manager.lease("test", 1, 0);
				
				if (set.size() == 0) {
					logger.info("set " + set);
				}
				
				Assertions.assertThat(set.size() != 0).isTrue();
				
				Document doc2 = set.iterator().next();
				
				Assertions.assertThat(doc2.get("id") != null).isTrue();
				
				if (idx %4 == 0) {
					manager.remove(doc2, false, true);
					deleted.add(doc2.get("id", GUID.class));
				}
				
				if (idx % 200 == nextCrash) {
					manager.crash();
					
					manager = new TableManager("test");
					
					for (GUID guid : inserted) {
						Key key = new Key(guid);
						Document doc = manager.get(key);
						
						if (deleted.contains(guid)) {
							Assertions.assertThat(doc == null).isTrue();
						} else {
							Assertions.assertThat(doc != null).isTrue();
						}
					}
				}
			}		
			
			manager.close();
		} catch (AssertionFailedError err) {
			throw err;
		} catch(Exception exc) {
			exc.printStackTrace();
			Assertions.fail(exc.getMessage());
		}
		
	}
}
