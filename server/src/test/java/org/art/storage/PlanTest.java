package org.art.storage;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;

import org.art.db.common.Comparison;
import org.art.db.common.Document;
import org.art.db.common.DocumentSet;
import org.art.db.common.Filter;
import org.art.db.query.Plan;
import org.art.db.query.Planner;
import org.art.db.storage.ColumnInfo;
import org.art.db.storage.IndexInfo;
import org.art.db.storage.Metadata;
import org.art.db.storage.StorageManager;
import org.art.db.storage.TableManager;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PlanTest {
	
	private static final Logger logger = LoggerFactory.getLogger(PlanTest.class);
	
	private File dbDir = new File("./dbroot");
	
	private static String[] colors = new String[] {"red", "blue", "green", "white", "black" };
	
	private static String[] models = new String[] {"ford", "gm", "dodge", "toyoya", "honda" };
	
	@BeforeEach
	public void beforeEach() throws Exception {
		this.dbDir.mkdir();
		
		File[] files = this.dbDir.listFiles();
		
		if (files != null) {
			for (File file : files) {
				file.delete();
			}
		}
		
	}
	
	private Document getDocument(int idx) throws Exception {
		
		Document doc = new Document();
		
		
		doc.put("id", String.valueOf(idx));
		doc.put("name", getName(idx));
		doc.put("amount", getAmount(idx));
		doc.put("color", colors[idx % colors.length]);
		doc.put("model", models[idx % models.length]);
		
		return doc;
	}
	
	private String getName(int idx) {
		return "Name" + idx;
	}
	
	private String getAmount(int idx) {
		return String.valueOf(idx + 100);
	}
	
	private void doQuery(TableManager storage, Metadata metadata, int[] expectedIds, Filter...filters) throws Exception {
		List<Filter> filterList = Arrays.asList(filters);
		
		Plan plan = Planner.buildPlan(metadata.name(), Collections.emptyList(), filterList);
		
		logger.info("Got plan " + plan);
		
		DocumentSet docs = plan.execute(storage);
		DocumentSet expected = new DocumentSet();
		
		for (int id : expectedIds) {
			expected.add(getDocument(id));
		}
		
		Assertions.assertThat(docs != null).isTrue();
		Assertions.assertThat(docs.size() == expectedIds.length).isTrue();
		
		List<Document> expectedList = new ArrayList<>();
		List<Document> actualList = new ArrayList<>();
		
		for (Document doc : expected) {
			expectedList.add(doc);
		}
		
		for (Document doc : docs) {
			actualList.add(doc);
		}
		
		Collections.sort(expectedList, (x, y) -> ((String)x.get("id")).compareTo(((String)y.get("id"))));
		Collections.sort(actualList, (x, y) -> ((String)x.get("id")).compareTo(((String)y.get("id"))));
		
		if (!expectedList.equals(actualList)) {
			logger.info("docs = " + docs + " expected = " + expected);
		}
			
		Assertions.assertThat(expectedList.equals(actualList)).isTrue();
		
		expected.close();
		docs.close();
			
		
	}
	
	private Metadata getMetadata(String name, String fields, String primaryKeys, String indices) throws Exception {
		SortedMap<String, ColumnInfo> fieldMap = new TreeMap<>();
		List<String> primaryKeyList = new ArrayList<>();
		List<IndexInfo>  indexList = new ArrayList<>();
		
		for (String field : fields.split(",")) {
			fieldMap.put(field, new ColumnInfo(String.class, false, true, null));
		}
		
		for (String field : primaryKeys.split(",")) {
			primaryKeyList.add(field);
		}
		
		for (String index : indices.split(",")) {
			String[] tokens = index.split("-");
			String indexName = tokens[0];
			boolean unique = tokens[1].equalsIgnoreCase("N");
			List<String> keys = new ArrayList<>();
			
			for (int idx = 2; idx < tokens.length; idx++) {
				keys.add(tokens[idx]);
			}
			
			IndexInfo info = new IndexInfo(indexName, unique, keys);
			logger.info("add " + info);
			indexList.add(info);
		}
		
		Metadata metadata = new Metadata(name, primaryKeyList, fieldMap, indexList);
		
		Metadata.setMetadata(metadata);
		
		return metadata;
	}
	
	@Test
	public void test() {
		try {
			Metadata metadata = getMetadata("test", "id,name,amount,color,model", "id", "name-N-name,amount-N-amount,modelAndColor-Y-model-color");
			
			TableManager manager = StorageManager.getInstance().getTableManager("test");
			
			for (int idx = 0; idx < 10; idx++) {
				manager.insert(this.getDocument(idx), true);
			}
					                       
			doQuery(manager,
					metadata,
					new int[] {1},
					new Filter(Comparison.EQ, "name", "Name1"));
			
			doQuery(manager,
					metadata,
					new int[] {1},
					new Filter(Comparison.EQ, "id", "1"));
					
			doQuery(manager,
					metadata,
					new int[] {1},
					new Filter(Comparison.EQ, "name", "Name1"));
			
			doQuery(manager,
					metadata,
					new int[] {1, 6},
					new Filter(Comparison.EQ, "model", models[1]),
					new Filter(Comparison.EQ, "color", colors[1]));
			      
		} catch (AssertionFailedError err) {
			throw err;
		} catch(Throwable exc) {
			exc.printStackTrace();
			Assertions.fail(exc.getMessage());
		}
	}
		
}
