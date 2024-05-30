package org.art.db.query;

import java.util.List;

import org.art.db.common.Document;
import org.art.db.common.Key;
import org.art.db.common.DocumentSet;
import org.art.db.common.Filter;
import org.art.db.storage.TableManager;

public class TableInLookup extends Plan {
	private final List<Comparable<?>> inClause;
	
	public TableInLookup(String table, List<Filter> filters, final List<Comparable<?>> inClause) throws Exception {
		super(table, filters);
		
		this.inClause = inClause;
	}
	
	
	@Override
	public DocumentSet execute(TableManager engine) throws Exception {
		DocumentSet result = new DocumentSet();
		
		for (Comparable<?> value : this.inClause) {
			Document doc = engine.getDocument(new Key(value));

			if (doc != null && super.matches(doc, filters)) {		
				result.add(doc);
			}
		}

		return result;

	}


	@Override
	public String toString() {
		return "TableLookupIn [inClause=" + inClause + "]";
	}

}
