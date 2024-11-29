package org.art.db.query;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

import org.art.db.common.Document;
import org.art.db.common.DocumentSet;
import org.art.db.common.Filter;
import org.art.db.common.Key;
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
		List<Key> list = null;
		
		for (Scan scan : this.scans) {
			if (list == null) {
				list = engine.getAll(scan.getIndex(), scan.getComparison(), scan.getKey());
			} else {
				list = this.merge(list, engine.getAll(scan.getIndex(), scan.getComparison(), scan.getKey()));
			}
		}
			
		DocumentSet result = new DocumentSet();
		
		for (Key key : list) {
			Document doc = engine.getDocument(key, lock);
			
			if (doc != null && this.matches(key, doc, this.filters)) {
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

	private List<Key> merge(List<Key> list1, List<Key> list2) {
		if (list1.size() >= list2.size()) {
			List<Key> result = new LinkedList<>();
			Iterator<Key> iterator1 = list1.iterator();
			Iterator<Key> iterator2 = list2.iterator();
			Key key1 = null;
			Key key2 = null;

			while (iterator1.hasNext()) {

				if (key1 == null && iterator1.hasNext()) {
					key1 = iterator1.next();
				}

				if (key2 == null && iterator2.hasNext()) {
					key2 = iterator2.next();
				}
				
				int comp;
				
				if (key2 == null || ( comp = key1.compareTo(key2)) < 0) {
					result.add(key1);
					key1 = null;
				} else if (comp == 0) {
					result.add(key1);
					key1 = null;
					key2 = null;
				} else {
					result.add(key2);
					key2 = null;
				}
			}

			return result;
		} else {
			return this.merge(list2, list2);

		}
	}

	@Override
	public String toString() {
		return "IndexLookup [scans=" + scans + ",  filters=" + filters + "]";
	}

	
}
