package org.art.storage;

import java.io.InputStream;
import java.nio.charset.Charset;

import org.apache.commons.io.IOUtils;
import org.art.db.common.Configuration;
import org.art.db.common.Document;
import org.art.db.common.DocumentSet;
import org.art.db.storage.StorageManager;
import org.art.db.storage.TableManager;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LargeDocumentTest extends BaseSQLTest {
	
	@SuppressWarnings("unused")
	private static final Logger logger = LoggerFactory.getLogger(LargeDocumentTest.class);
	
	
	@Test
	public void test() {
		try {
			registerMetadata("SYS.SYS.largedoc", "id,text", "id", "");
			
			Configuration.getInstance().setProperty("org.art.db.documentCache", "0M");
			
			TableManager manager = StorageManager.getInstance().getTableManager("SYS.SYS.largedoc");
			String text;
			
			try (InputStream stream = this.getClass().getClassLoader().getResourceAsStream("./large.txt")) {
				text = IOUtils.toString(stream, Charset.defaultCharset());
			}
			
			Document doc = new Document();
			
			doc.put("id", "key");
			doc.put("text", text);
			doc.put("text1", text);
			
			manager.insert(doc, false, true);
			
			DocumentSet docs = super.executeSelect("select * from largedoc where id = 'key'");
			
			Assertions.assertThat(!docs.isEmpty()).isTrue();
			
			Document first = docs.iterator().next();
			
			Assertions.assertThat(first.get("text").equals(text));
			
			manager.close();
			
			manager = StorageManager.getInstance().getTableManager("SYS.SYS.largedoc");
			docs = super.executeSelect("select * from largedoc where id = 'key'");
			
			Assertions.assertThat(!docs.isEmpty()).isTrue();
			
			first = docs.iterator().next();
			
			Assertions.assertThat(first.get("text").equals(text));
		} catch (AssertionFailedError err) {
			throw err;
		} catch(Exception exc) {
			exc.printStackTrace();
			Assertions.fail(exc.getMessage());
		} finally {
			Configuration.getInstance().removeProperty("org.art.db.documentCache");
		}
	}
		
}
