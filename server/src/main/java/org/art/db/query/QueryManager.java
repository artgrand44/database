package org.art.db.query;

import java.util.List;
import java.util.Map;

import org.art.db.common.DBNull;
import org.art.db.common.Document;
import org.art.db.common.DocumentComparer;
import org.art.db.common.DocumentSet;
import org.art.db.common.Filter;
import org.art.db.common.OrderBy;
import org.art.db.storage.ColumnInfo;
import org.art.db.storage.Metadata;
import org.art.db.storage.StorageManager;
import org.art.db.storage.TableManager;

public class QueryManager {
	private static QueryManager instance = new QueryManager();
	
	public static QueryManager getInstance() {
		return instance;
	}
	
	public void insert(String table, Document doc) throws Exception {
		TableManager manager = StorageManager.getInstance().getTableManager(table);
		
		manager.insert(doc, true);
	}
	
	@SuppressWarnings("resource")
	public DocumentSet select(String table, List<String> selectColumns, List<OrderBy> orderBy, List<Filter> filters, long limit, boolean lock) throws Exception {
		
		// Find the documents that match the filter
		
		Metadata metadata = Metadata.getMetadata(table);
		Plan plan = Planner.buildPlan(table, selectColumns, filters);
		TableManager manager = StorageManager.getInstance().getTableManager(table);
				
		DocumentSet set = plan.execute(manager, limit, lock);
		
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

		return set;
	}

	
	public void remove(String table, Document doc) throws Exception {
		TableManager manager = StorageManager.getInstance().getTableManager(table);
		
		manager.remove(doc, true);
		
	}
	
	public void unlock(String table, Document doc)  {
		TableManager manager = StorageManager.getInstance().getTableManager(table);
		
		manager.unlock(doc);
		
	}
	
	public void update(String table, Document oldDoc, Document newDoc) throws Exception {
		TableManager manager = StorageManager.getInstance().getTableManager(table);
		
		manager.update(oldDoc, newDoc, true);
	}
	
}
