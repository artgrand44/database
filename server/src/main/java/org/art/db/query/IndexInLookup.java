package org.art.db.query;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.art.db.common.Comparison;
import org.art.db.common.Document;
import org.art.db.common.Key;
import org.art.db.common.DocumentSet;
import org.art.db.common.Filter;
import org.art.db.storage.TableManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class IndexInLookup extends Plan {
	@SuppressWarnings("unused")
	private static final Logger logger = LoggerFactory.getLogger(IndexLookup.class);
	
	private final String indexName;
	private final List<Comparable<?>> inClause;
	
	public IndexInLookup(String table, List<Filter> filters, String indexName,  List<Comparable<?>> inClause) throws Exception {
		super(table, filters);
		
		this.indexName = indexName;
		this.inClause = inClause;
		
	}
	
	@Override
	public DocumentSet execute(TableManager engine) throws Exception {
		Set<Key> keys = new HashSet<>();
		
		for (Comparable<?> value : this.inClause) {
			keys.addAll(engine.getAll(this.indexName, Comparison.EQ, new Key(value)));
		}
		
		DocumentSet result = new DocumentSet();
		
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
		return "IndexInLookup [indexName=" + indexName + ", inClause=" + inClause + "]";
	}


}
