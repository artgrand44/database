package org.art.storage;


import java.io.File;
import java.util.Collections;
import java.util.Objects;
import java.util.Random;
import java.util.SortedMap;
import java.util.TreeMap;

import org.apache.commons.io.FileUtils;
import org.art.db.common.Document;
import org.art.db.common.Key;
import org.art.db.storage.ColumnInfo;
import org.art.db.storage.Environment;
import org.art.db.storage.MemorySegment;
import org.art.db.storage.Metadata;
import org.art.db.storage.TableManager;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.opentest4j.AssertionFailedError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class LogDirTableCrashTest extends BaseTest {
	
	private static final Logger logger = LoggerFactory.getLogger(MemorySegment.class);
	
	private Document createDocument(int idx) throws Exception {
		Document doc = new Document();
		
		doc.put("id", "Key" + idx);
		doc.put("value", "value" + idx);
		
		return doc;
	}
	
	@BeforeEach
	public void beforeEach() throws Exception {
		super.beforeEach();
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
	@RepeatedTest(5)
	public void test() {
		try {
			File directory = new File("./atest");
			File file = new File(directory, "test.log");
			
			file.mkdirs();
			file.delete();
			
			FileUtils.deleteDirectory(new File("./dbroot/test"));
			
			new File("./dbroot/test").mkdirs();
			
			Environment.getInstance().load("./logdirtinybatch.properties");
			
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
			
			for (int idx = 0; idx < 250; idx++) {
				Document doc1 = createDocument(idx);
				
				if (idx % 50 == 0) {
					logger.info("***insert " + idx);
				}
				
				manager.insert(doc1, true, true);
				
				if (idx % 200 == nextCrash) {
					logger.info("****crash " + idx);
					
					manager.crash();
					
					manager = new TableManager("test");
					
					logger.info("****reopened " + idx);
					for (int jdx = 0; jdx <= idx; jdx++) {
						Key key = new Key("Key" + jdx);
						Document doc = manager.get(key);
						
						if (doc == null) {
							logger.info("no doc for " + key);
							doc = manager.get(key);
							logger.info("second get " + key + " " + doc);
						}
						Assertions.assertThat(checkDocument(doc, jdx)).isTrue();
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
