package org.art.query;

import java.util.List;

import org.art.db.common.Document;
import org.art.db.common.Key;
import org.art.db.common.ServerDocumentSet;
import org.art.storage.TableManager;

public class TableInLookup extends Plan {
	private final List<Comparable<?>> inClause;
	
	public TableInLookup(String table, List<Filter> filters, final List<Comparable<?>> inClause) throws Exception {
		super(table, filters);
		
		this.inClause = inClause;
	}
	
	
	@Override
	public ServerDocumentSet execute(TableManager engine) throws Exception {
		ServerDocumentSet result = new ServerDocumentSet();
		
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
