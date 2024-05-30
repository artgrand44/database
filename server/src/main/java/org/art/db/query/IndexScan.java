package org.art.db.query;

import java.util.List;
import java.util.Set;

import org.art.db.common.Document;
import org.art.db.common.Key;
import org.art.db.common.DocumentSet;
import org.art.db.common.Filter;
import org.art.db.storage.TableManager;

public class IndexScan extends Plan {

	private final Scan scan;	

	private final Key key;
	private final List<String> keyFields;
	private final List<String> selectColumns;
	
	public IndexScan(String table, List<Filter> filters, List<String> selectColumns, Scan scan, List<String> keyFields, Key key) throws Exception {
		super(table, filters);
		
		this.scan = scan;
		this.keyFields = keyFields;
		this.selectColumns = selectColumns;
		this.key = key;
		
	}
	
	@Override
	public DocumentSet execute(TableManager engine) throws Exception {
		Set<Key> keys = engine.getAllKeys(scan.getIndex(), scan.getComparison(), key);
		DocumentSet result = new DocumentSet();
		
		for (Key key : keys) {
			Document doc = new Document();
			Comparable<?>[] parts = key.getKeys();
			
			for (String name : this.selectColumns) {
				int index = this.keyFields.indexOf(name);
				
				doc.put(name, parts[index]);
				
				if (this.matches(doc, this.filters)) {
					result.add(doc);
				}
			}	
		}
					
		return result;
		
	}

	@Override
	public String toString() {
		return "IndexScan [scan=" + scan + ", keyFields=" + keyFields + ", filters=" + filters + "]";
	}

}
