package org.art.db.query;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;

import org.art.db.common.Document;
import org.art.db.common.Key;
import org.art.db.common.DocumentSet;
import org.art.db.storage.ColumnInfo;
import org.art.db.storage.Comparison;
import org.art.db.common.DBNull;
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
	
	@SuppressWarnings("resource")
	public DocumentSet select(String table, List<String> selectColumns, List<OrderBy> orderBy, List<Filter> filters, long offset, long limit) throws Exception {
		
		// Find the documents that match the filter
		
		Metadata metadata = Metadata.getMetadata(table);
		Plan plan = Planner.buildPlan(table, selectColumns, filters);
		TableManager manager = StorageManager.getInstance().getTableManager(table);
				
		DocumentSet set = plan.execute(manager);
		
		// Go through the documents, find the null values, and merge in the defaults
		
		for (Document doc : set) {
			for (Map.Entry<String, ColumnInfo> entry : metadata.columns().entrySet()) {
				String name = entry.getKey();
				ColumnInfo info = entry.getValue();
				Object value = doc.get(name);
				
				if (value == DBNull.NULL && ! metadata.column(name).nullable()) {
					doc.put(name, info.defaultValue());
				}
				
			}
		}
		
		if (orderBy != null && !orderBy.isEmpty()) {
			set.sort(new DocumentComparer(orderBy));
		}
		
		// Handle the selected columns
		
		if (selectColumns != null && !selectColumns.isEmpty()) {
			DocumentSet newSet = new DocumentSet();

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
			
			set = newSet;
		}


		// Handle the offset and limit clauses
		
		if (offset != 0 || limit != Long.MAX_VALUE) {
			DocumentSet newSet = new DocumentSet();
			long index = 0;

			for (Document doc : set) {
				if (newSet.size() == limit) { 
					break;
				} else if (index >= offset) {
					newSet.add(doc);
				}
				
				index++;
			}

			set = newSet;
		}
		
		
		return set;
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
