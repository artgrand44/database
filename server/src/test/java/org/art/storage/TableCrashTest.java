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


public class TableCrashTest  {
	
	private static final Logger logger = LoggerFactory.getLogger(TableCrashTest.class);
	
	private Document createDocument(int idx) throws Exception {
		Document doc = new Document();
		
		doc.put("id", "Key" + idx);
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
	
	private boolean checkDocument(Document doc, int idx) {
		if (doc == null) {
			return false;
		}
		if (!Objects.equals(doc.get("value"), "value" + idx)) {
			logger.info("oops");
		}
		return Objects.equals(doc.get("value"), "value" + idx);
	}
	
	//@Test
	@RepeatedTest(1)
	public void test() {
		try {
			File directory = new File("./atest");
			File file = new File(directory, "test.log");
			
			file.mkdirs();
			file.delete();
			
			FileUtils.deleteDirectory(new File("./dbroot/test"));
			
			new File("./dbroot/test").mkdirs();
			
			Configuration.getInstance().load("./tinybatch.properties");
			
			ColumnInfo valueInfo = new ColumnInfo(String.class, false, false, "");
			ColumnInfo idInfo = new ColumnInfo(String.class, false, false, "");
			SortedMap<String, ColumnInfo> columns = new TreeMap<>();
			
			columns.put("value", valueInfo);
			columns.put("id", idInfo);
			
			Metadata metadata = new Metadata("test", Collections.singletonList("id"), columns, Collections.emptyList(), false);
			
			Metadata.setMetadata(metadata);
			
			TableManager manager = new TableManager("test");
			
			Random random = new Random();
			int nextCrash = random.nextInt(200);
			Set<String> deleted = new HashSet<>();
			
			for (int idx = 0; idx < 250; idx++) {
				Document doc1 = createDocument(idx);
				
				if (idx % 50 == 0) {
					logger.info("test inserting " + idx);
				}
				
				manager.insert(doc1, true, true);
				
				if (idx %4 == 0) {
					manager.remove(doc1, false, true);
					deleted.add(doc1.get("id", String.class));
				}
				
				if (idx % 200 == nextCrash) {
					logger.info("****crash " + idx);
					
					manager.crash();
					
					manager = new TableManager("test");
					
					logger.info("test reopened" + idx);
					for (int jdx = 0; jdx <= idx; jdx++) {
						Key key = new Key("Key" + jdx);
						
						if (deleted.contains("Key" + jdx)) {
							logger.info("getting " + ("Key" + jdx));
						}
						Document doc = manager.get(key);
						
						if (deleted.contains("Key" + jdx)) {
							if (doc != null) {
								logger.info("got doc " + doc);
							}
							Assertions.assertThat(doc == null).isTrue();
						} else {
							if (doc == null) {
								logger.info("no doc for " + key + " deleted " + deleted);
							}
							Assertions.assertThat(doc != null).isTrue();
							Assertions.assertThat(checkDocument(doc, jdx)).isTrue();
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
