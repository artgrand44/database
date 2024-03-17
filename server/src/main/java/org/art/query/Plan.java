package org.art.query;

import java.util.ArrayList;
import java.util.List;

import org.art.storage.Comparison;
import org.art.db.common.Document;
import org.art.db.common.Key;
import org.art.db.common.ServerDocumentSet;
import org.art.storage.Metadata;
import org.art.storage.TableManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class Plan  {
	
	@SuppressWarnings("unused")
	private static final Logger logger = LoggerFactory.getLogger(Plan.class);
	
	protected final List<Filter> filters;
	protected final String table;
	protected final Metadata metadata;
	
	protected Plan(String table, List<Filter> filters) throws Exception {
		this.filters = filters;
		this.table = table;
		this.metadata = Metadata.getMetadata(table);
	}
	
	public abstract ServerDocumentSet execute(TableManager engine) throws Exception;
	
	protected Key buildKey(List<String> keys, List<Filter> filters) {
		List<Comparable<?>> fields = new ArrayList<>();
		
		for (String name : keys) {
			for (Filter filter : filters) {
				if (filter.getField().equals(name)) {
					fields.add((Comparable<?>)filter.getValue());
				}
			}
		}
		
		return new Key(fields);
	}
	
		
	protected boolean matches(Document doc, List<Filter> filters) {
		for (Filter filter : filters) {
			Object actual = doc.get(filter.getField());
			Object expected = filter.getValue(); 
			Comparison comparison = filter.getComparison();
			
			if (!comparison.matches(actual, expected, expected.getClass())) {
				return false;
			}	
		}
		
		return true;
	}
			
}
