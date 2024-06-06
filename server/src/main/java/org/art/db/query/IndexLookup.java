package org.art.db.query;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.art.db.common.Document;
import org.art.db.common.Key;
import org.art.db.common.DocumentSet;
import org.art.db.common.Filter;
import org.art.db.storage.TableManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class IndexLookup extends Plan {

	@SuppressWarnings("unused")
	private static final Logger logger = LoggerFactory.getLogger(IndexLookup.class);
			
	private final List<Scan> scans;
	
	public IndexLookup(String table, List<Filter> filters, List<Scan> scans) throws Exception {
		super(table, filters);
		this.scans = scans;
	}
	
	@Override
	public DocumentSet execute(TableManager engine, long limit, boolean lock) throws Exception {
		Set<Key> keys = null;
		
		for (Scan scan : this.scans) {
			if (keys == null) {
				keys = engine.getAll(scan.getIndex(), scan.getComparison(), scan.getKey());
			} else {
				keys.retainAll(engine.getAll(scan.getIndex(), scan.getComparison(), scan.getKey()));
			}
		}
			
		DocumentSet result = new DocumentSet();
		
		List<Key> list = new ArrayList<>(keys);
		
		Collections.sort(list);
		
		for (Key key : list) {
			Document doc = engine.getDocument(key, lock);
			
			if (doc != null && this.matches(doc, this.filters)) {
				result.add(doc);
			} else if (lock) {
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
		return "IndexLookup [scans=" + scans + ",  filters=" + filters + "]";
	}

	
}
