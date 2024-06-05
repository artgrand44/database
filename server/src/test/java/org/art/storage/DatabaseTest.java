package org.art.storage;



import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

import org.art.db.common.Comparison;
import org.art.db.common.Document;
import org.art.db.common.Key;
import org.art.db.storage.ColumnInfo;
import org.art.db.storage.IndexInfo;
import org.art.db.storage.Metadata;
import org.art.db.storage.StorageManager;
import org.art.db.storage.TableManager;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DatabaseTest extends BaseTreeTest {
	
	private static final Logger logger = LoggerFactory.getLogger(DatabaseTest.class);
	
	private Document getDocument(String id, String name) throws Exception {
		Document doc = new Document();
		
		doc.put("id", id);
		doc.put("name", name);
		
		return doc;
	}
	
	
	private boolean checkDocument(Document doc, String id, String name) {		
		return doc == null ? false : Objects.equals(doc.get("id"), id) && Objects.equals(doc.get("name"), name); 
	}
	
	@Test
	public void test() {
		try {
			List<String> primaryKeysFields = new ArrayList<>();
			List<String> keyFields = new ArrayList<>();
			
			primaryKeysFields.add("id");
			keyFields.add("name");
			
			TreeMap<String, ColumnInfo> columns = new TreeMap<>(Map.of("id", new ColumnInfo(String.class, false, false, null),
					                                                   "name", new ColumnInfo(String.class, false, false, null)));
			
			Metadata metadata = new Metadata("test1",
											primaryKeysFields,
					                    	columns,
					                    	Collections.singletonList(new IndexInfo("name", false, keyFields)));
			
			Metadata.setMetadata(metadata);
			TableManager manager = StorageManager.getInstance().getTableManager("test1");
			
			for (int idx = 0; idx < 10; idx++) {
				manager.insert(this.getDocument(String.valueOf(idx), "Name" + idx), true);
			}
					                       
			for (int idx = 0; idx < 10; idx++) {
				Set<Key> keys = manager.getAll("name", Comparison.EQ,  new Key("Name" + idx));
				
				Assertions.assertThat(keys.size() == 1).isTrue();
				
				Document doc = manager.getDocument(keys.iterator().next(), false);
				Assertions.assertThat(this.checkDocument(doc, String.valueOf(idx), "Name" + idx)).isTrue();
			}
			logger.info("end");
		} catch (AssertionFailedError err) {
			throw err;
		} catch(Exception exc) {
			exc.printStackTrace();
			Assertions.fail(exc.getMessage());
		}
	}
		
}
