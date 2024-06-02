package org.art.storage;

import java.util.UUID;

import org.apache.commons.lang3.StringUtils;
import org.art.db.common.Document;
import org.art.db.common.DocumentSet;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DocumentSetTest extends BaseSQLTest {
	
	@SuppressWarnings("unused")
	private static final Logger logger = LoggerFactory.getLogger(DocumentSetTest.class);
	
	@Test
	public void test() {
		try {
			DocumentSet set = new DocumentSet();
			
			
			for (int idx = 0; idx < 2000; idx++) {
				Document doc = new Document();
				
				doc.put("key", "key" + StringUtils.leftPad(String.valueOf(idx), 5));
				
				for (int jdx = 0; jdx < 10; jdx++) {
					String str = UUID.randomUUID().toString();
					
					doc.put(str, str);
				}
				
				set.add(doc);
				Assertions.assertThat(set.size() == idx + 1).isTrue();
				
				if (idx > 1005) {
					Assertions.assertThat(set.hasFile()).isTrue();
				}				
			}
			

			Assertions.assertThat(set.size() == 2000).isTrue();
			
			long count = 0;
			for (@SuppressWarnings("unused") Document doc : set) {
				count++;
			}
			
			Assertions.assertThat(count == 2000).isTrue();
			
			set.freeze();
			
			count = 0;
			for (@SuppressWarnings("unused") Document doc : set) {
				count++;
			}
			
			Assertions.assertThat(count == 2000).isTrue();
			
			set.sort(this::compare);
			
			String prev = "";
			
			for (Document doc : set) {
				String key = doc.get("key", String.class);
				
				Assertions.assertThat(prev.compareTo(key) < 0).isTrue();
				prev = key;
			}
			
			set.close();
		} catch (AssertionFailedError err) {
			throw err;
		} catch(Exception exc) {
			exc.printStackTrace();
			Assertions.fail(exc.getMessage());
		}
	}
	
	private int compare(Document doc1, Document doc2) {
		String key1 = (String)doc1.get("key");
		String key2 = (String)doc2.get("key");
		
		return key1.compareTo(key2);
	}
}
