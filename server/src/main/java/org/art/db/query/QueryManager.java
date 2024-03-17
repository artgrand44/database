package org.art.db.query;

import java.util.Comparator;
import java.util.List;

import org.art.db.common.Document;
import org.art.db.common.Key;
import org.art.db.common.ServerDocumentSet;
import org.art.db.storage.Comparison;
import org.art.db.storage.DBNull;
import org.art.db.storage.Metadata;
import org.art.db.storage.StorageManager;
import org.art.db.storage.TableManager;

public class QueryManager {
	private static QueryManager instance = new QueryManager();
	
	public static QueryManager getInstance() {
		return instance;
	}
	
	public Document get(String table, Key key)  throws Exception {
		TableManager manager = StorageManager.getInstance().getTableManager(table);
		
		return manager.getDocument(key);
		
	}
	
	public void insert(String table, Document doc) throws Exception {
		TableManager manager = StorageManager.getInstance().getTableManager(table);
		
		manager.insert(doc, true);
	}
	
	public ServerDocumentSet select(String table, List<String> selectColumns, List<OrderBy> orderBy, List<Filter> filters) throws Exception {
		Metadata metadata = Metadata.getMetadata(table);
		Plan plan = Planner.buildPlan(table, selectColumns, filters);
		TableManager manager = StorageManager.getInstance().getTableManager(table);
		
		ServerDocumentSet set = plan.execute(manager);
		
		if (orderBy != null && !orderBy.isEmpty()) {
			set.sort(new DocumentComparer(orderBy));
		}
			
		if (selectColumns == null || selectColumns.isEmpty()) {
			return set;
		}
		
		ServerDocumentSet newSet = new ServerDocumentSet();
		
		for (Document doc : set) {
			Document newDoc = new Document();

			for (String name : selectColumns) {
				Object value = doc.get(name);

				if (value == DBNull.NULL && ! metadata.column(name).nullable()) {
					value = metadata.column(name).defaultValue();
				}

				newDoc.put(name, value);
			}

			newSet.add(newDoc);
		}

		return newSet;
	}
	
	
	public void remove(String table, Document doc) throws Exception {
		TableManager manager = StorageManager.getInstance().getTableManager(table);
		
		manager.remove(doc, true);
		
	}
	
	public void update(String table, Document oldDoc, Document newDoc) throws Exception {
		TableManager manager = StorageManager.getInstance().getTableManager(table);
		
		manager.update(oldDoc, newDoc, true);
	}
	
	static class DocumentComparer implements Comparator<Document> {
		private final List<OrderBy> orderBy;
		
		DocumentComparer(List<OrderBy> orderBy) {
			this.orderBy = orderBy;
		}
		
		public int compare(Document first, Document second) {
			
			for (OrderBy orderBy : this.orderBy) {
				String name = orderBy.field();

				Object firstField = first.get(name);
				Object secondField = second.get(name);

				if (firstField instanceof DBNull && ! (secondField instanceof DBNull)) {
					return -1;
				}
				
				if (secondField instanceof DBNull && ! (firstField instanceof DBNull)) {
					return +1;
				}
				
				int comp = Comparison.compare(firstField, secondField);

				if (comp != 0) {
					return orderBy.ascending() ? comp : - comp;
				}

			}
			
			return 0;
		}
	}

}
