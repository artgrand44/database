package org.art.storage;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.art.db.common.Document;
import org.art.db.common.DocumentSet;
import org.art.db.storage.StorageManager;
import org.art.db.storage.TableManager;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class QueryTest extends BaseSQLTest {
	
	@SuppressWarnings("unused")
	private static final Logger logger = LoggerFactory.getLogger(QueryTest.class);
	
	private static String[] colors = new String[] {"red", "blue", "green", "white", "black" };
	
	private static String[] models = new String[] {"ford", "gm", "dodge", "toyoya", "honda" };
	
	
	
	private Document getDocument(int idx) throws Exception {
		
		Document doc = new Document();
		//d,name,amount,color,model
		
		doc.put("id", String.valueOf(idx));
		doc.put("name", getName(idx));
		doc.put("amount", getAmount(idx));
		doc.put("color", colors[idx % colors.length]);
		doc.put("model", models[idx % models.length]);
		doc.put("alias", idx % 2 == 0 ? getName(idx) : "aka");
		
		
		return doc;
	}
	
	private String getName(int idx) {
		return "Name" + idx;
	}
	
	private String getAmount(int idx) {
		return String.valueOf(idx + 100);
	}
	
	private void doQuery(TableManager storage, int[] expectedIds, String projections, String sql) throws Exception {
		List<String> projectionList = projections == null ? null : Arrays.asList(projections.split(","));
		
		DocumentSet docs = super.executeSelect(sql);
		DocumentSet expected = new DocumentSet();
		
		for (int id : expectedIds) {
			if (projections == null) {
				expected.add(getDocument(id));
			} else {
				Document doc = new Document();
				
				if (projectionList.contains("id")) {
					doc.put("id", String.valueOf(id));
				}
				
				if (projectionList.contains("name")) {
					doc.put("name", this.getName(id));
				}
				
				if (projectionList.contains("amount")) {
					doc.put("amount", this.getAmount(id));
				}
				
				expected.add(doc);
			}
		}
		
		Assertions.assertThat(docs != null).isTrue();
		
		Set<Document> expectedSet = new HashSet<>();
		Set<Document> actualSet = new HashSet<>();
		
		for (Document doc : expected) {
			expectedSet.add(doc);
		}
		
		for (Document doc : docs) {
			actualSet.add(doc);
		}
		
		Assertions.assertThat(docs.size() == expectedIds.length).isTrue();
		Assertions.assertThat(expectedSet.equals(actualSet)).isTrue();		
		
		expected.close();
		docs.close();
		
	}
	
	
	@Test
	public void test() {
		try {
			registerMetadata("SYS.SYS.test", "id,name,amount,color,model,alias", "id", "name-N-name,amount-N-amount,modelAndColor-Y-model-color");
			
			TableManager manager = StorageManager.getInstance().getTableManager("SYS.SYS.test");
			
			for (int idx = 0; idx < 10; idx++) {
				manager.insert(this.getDocument(idx), true);
			}
					    
			doQuery(manager,
					new int[] {0, 1, 2, 3, 4},
					null,
					"select * from test where name >= 'Name0' order by name limit 5");
			
			doQuery(manager,
					new int[] {5, 6, 7, 8, 9},
					null,
					"select * from test where name >= 'Name0'  order by name offset 5 limit 5");
			
			doQuery(manager,
					new int[] {0, 2, 4, 6, 8},
					null,
					"select * from test where name >= 'Name0' and name = alias");
			
			doQuery(manager,
					new int[] {2, 3, 4},
					null,
					"select * from test where name between 'Name2' and 'Name4'");
			
			doQuery(manager,
					new int[] {1},
					null,
					"select * from test where name = 'Name1'");
			
			doQuery(manager,
					new int[] {1, 2},
					null,
					"select * from test where name in ('Name1', 'Name2' )");
			
			doQuery(manager,
					new int[] {1, 2},
					null,
					"select * from test where id in ('1', '2' )");
			
			doQuery(manager,
					new int[] {0, 1, 2},
					null,
					"select * from test where name < 'Name3'");
			
			doQuery(manager,
					new int[] {1, 2, 3, 4, 5, 6, 7, 8, 9},
					null,
					"select * from test where name >= 'Name1'");
			
			doQuery(manager,
					new int[] {0, 1, 2},
					null,
					"select * from test where name < 'Name3'");
			
			doQuery(manager,
					new int[] {0, 1, 2, 3},
					null,
					"select * from test where name <= 'Name3'");
			
			doQuery(manager,
					new int[] {0, 1, 2},
					null,
					"select * from test where name < 'Name3' and amount <" + getAmount(3));
			
			doQuery(manager,
					new int[] {1},
					null,
					"select * from test where id = '1'");
					
			doQuery(manager,
					new int[] {1},
					"name",
					"select name from test where name = 'Name1'");
			
			doQuery(manager,
					new int[] {0, 5},
					"name",
					"select name from test where model = 'ford' and color = 'red'");
			      
			doQuery(manager,
					new int[] {1},
					null,
					"select * from test where id = '1' or name = 'Name1'");
			
		} catch (AssertionFailedError err) {
			throw err;
		} catch(Exception exc) {
			exc.printStackTrace();
			Assertions.fail(exc.getMessage());
		}
	}
		
}
