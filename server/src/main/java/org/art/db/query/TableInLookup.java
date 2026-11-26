package org.art.db.query;

import java.util.List;

import org.art.db.common.Document;
import org.art.db.common.Key;
import org.art.db.common.DocumentSet;
import org.art.db.common.Filter;
import org.art.db.storage.Metadata;
import org.art.db.storage.TableManager;

public class TableInLookup extends Plan {
	private final List<Comparable<?>> inClause;
	
	public TableInLookup(String table, List<Filter> filters, final List<Comparable<?>> inClause, List<String> selectColumns, Metadata metadata) throws Exception {
		super(table, filters, selectColumns, metadata);
		
		this.inClause = inClause;
	}
	
	
	@Override
	public DocumentSet execute(TableManager engine, long limit, boolean lock) throws Exception {
		DocumentSet result = new DocumentSet();
		
		for (Comparable<?> value : this.inClause) {
			Key key = new Key(value);
			
			Document doc = engine.getDocument(key, lock);

			if (doc != null && super.matches(key, doc, filters)) {
				result.add(this.filterFields(doc));
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
		return "TableLookupIn [inClause=" + inClause + "]";
	}

}
