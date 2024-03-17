package org.art.db.query;

import java.util.List;
import java.util.Set;

import org.art.db.common.Document;
import org.art.db.common.Key;
import org.art.db.common.ServerDocumentSet;
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
	public ServerDocumentSet execute(TableManager engine) throws Exception {
		Set<Key> keys = null;
		
		for (Scan scan : this.scans) {
			if (keys == null) {
				keys = engine.getAll(scan.getIndex(), scan.getComparison(), scan.getKey());
			} else {
				keys.retainAll(engine.getAll(scan.getIndex(), scan.getComparison(), scan.getKey()));
			}
		}
			
		ServerDocumentSet result = new ServerDocumentSet();
		
		for (Key key : keys) {
			Document doc = engine.getDocument(key);
			
			if (doc != null && this.matches(doc, this.filters)) {
				result.add(doc);
			}
		}
				
		return result;
		
	}

	@Override
	public String toString() {
		return "IndexLookup [scans=" + scans + ",  filters=" + filters + "]";
	}

	
}
