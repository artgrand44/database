package org.art.db.query;

import java.util.List;

import org.art.db.common.Document;
import org.art.db.common.Key;
import org.art.db.common.DocumentSet;
import org.art.db.common.Filter;
import org.art.db.storage.TableManager;

public class TableLookup extends Plan {
	private final Key key;
	
	public TableLookup(String table, List<Filter> filters, Key key) throws Exception {
		super(table, filters);
		this.key = key;
	}
	
	
	@Override
	public DocumentSet execute(TableManager engine, long limit, boolean lock) throws Exception {
		DocumentSet result = new DocumentSet();
		Document doc = engine.getDocument(key, lock);
		
		if (doc != null && super.matches(doc, filters)) {		
			result.add(doc);
		} else if (lock) {
			engine.unlock(key);
		}
		
		return result;
		
	}


	@Override
	public String toString() {
		return "TableLookup [key=" + key + ", filters=" + filters + "]";
	}	

}
