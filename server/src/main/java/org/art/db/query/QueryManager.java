package org.art.db.query;

import java.util.List;

import org.art.db.common.Document;
import org.art.db.common.DocumentSet;
import org.art.db.common.Filter;
import org.art.db.storage.Metadata;
import org.art.db.storage.StorageManager;
import org.art.db.storage.TableManager;

public class QueryManager {
	
	private static QueryManager instance = new QueryManager();
	
	public static QueryManager getInstance() {
		return instance;
	}
	
	public void checkWriteLock(String table, Document doc)  {
		TableManager manager = StorageManager.getInstance().getTableManager(table);
		
		manager.checkWriteLock(doc);
		
	}
	
	public void insert(String table, Document doc) throws Exception {
		TableManager manager = StorageManager.getInstance().getTableManager(table);
		
		manager.insert(doc, true, true);
	}
	
	@SuppressWarnings("resource")
	public DocumentSet select(String table, List<String> selectColumns, List<Filter> filters, long limit, boolean lock) throws Exception {
		
		// Find the documents that match the filter
		
		Metadata metadata = Metadata.getMetadata(table);
		Plan plan = Planner.buildPlan(table, selectColumns, filters, metadata);
		TableManager manager = StorageManager.getInstance().getTableManager(table);
				
		DocumentSet set = plan.execute(manager, limit, lock);
		
		if (selectColumns == null || selectColumns.isEmpty()) {
			set.getColumns().addAll(metadata.getColumns().keySet());
		} else {
			set.getColumns().addAll(selectColumns);
		}
		
		return set;
	}

	
	public void remove(String table, Document doc) throws Exception {
		TableManager manager = StorageManager.getInstance().getTableManager(table);
		
		manager.remove(doc, true, true);
		
	}
	
	public void unlockPrimaryKey(String table, Document doc)  {
		TableManager manager = StorageManager.getInstance().getTableManager(table);
		
		manager.unlockPrimaryKey(doc);
		
	}
	
	public void unlockPrimaryAllKeys(String table, DocumentSet set)  {
		TableManager manager = StorageManager.getInstance().getTableManager(table);
		
		manager.unlockAllPrimaryKeys(set);		
	}
	
	public void update(String table, Document oldDoc, Document newDoc) throws Exception {
		TableManager manager = StorageManager.getInstance().getTableManager(table);
		
		manager.update(oldDoc, newDoc, true, true);
	}
	
}
