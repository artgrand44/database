package org.art.db.query;

import java.util.List;
import java.util.Set;

import org.art.db.common.Document;
import org.art.db.common.Key;
import org.art.db.common.DocumentSet;
import org.art.db.common.Filter;
import org.art.db.storage.TableManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class IndexBetweenLookup extends Plan {

	@SuppressWarnings("unused")
	private static final Logger logger = LoggerFactory.getLogger(IndexBetweenLookup.class);
			
	private final String indexName;
	private final Key first;
	private final Key last;
	
	public IndexBetweenLookup(String table, List<Filter> filters, String indexName, Key first, Key last) throws Exception {
		super(table, filters);
		
		this.indexName = indexName;
		this.first = first;
		this.last = last;
	}
	
	@Override
	public DocumentSet execute(TableManager engine, long limit, boolean lock) throws Exception {
		Set<Key> keys = engine.getAllBetween(this.indexName, this.first, this.last);
		
			
		DocumentSet result = new DocumentSet();
		
		for (Key key : keys) {
			Document doc = engine.getDocument(key, lock);
			
			if (doc != null && this.matches(key, doc, this.filters)) {
				result.add(doc);
			} else if (lock){
				engine.unlockPrimaryKey(key);
			}
			
			if (result.size() == limit) {
				break;
			}
		}
				
		return result;
		
	}

	@Override
	public String toString() {
		return "IndexBetweenLookup [indexName=" + indexName + ", first=" + first + ", last=" + last + "]";
	}

	
	
}
