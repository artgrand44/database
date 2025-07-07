package org.art.db.query;

import java.util.ArrayList;
import java.util.List;

import org.art.db.common.Comparison;
import org.art.db.common.DBNull;
import org.art.db.common.DiscoveryHandler;
import org.art.db.common.Document;
import org.art.db.common.DocumentSet;
import org.art.db.common.Filter;
import org.art.db.common.Key;
import org.art.db.storage.TableManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class Plan  {
	
	@SuppressWarnings("unused")
	private static final Logger logger = LoggerFactory.getLogger(Plan.class);
	
	protected final List<Filter> filters;
	protected final String table;
	
	protected Plan(String table, List<Filter> filters) throws Exception {
		this.filters = filters;
		this.table = table;;
	}
	
	public abstract DocumentSet execute(TableManager engine, long limit, boolean lock) throws Exception;
	
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
	
		
	protected boolean matches(Key key, Document doc, List<Filter> filters) {
		
		// If the document has been moved to another shard then return false.
		
		DiscoveryHandler handler = DiscoveryHandler.getServerInstance();
		
		if (handler != null && !handler.getShard(key.toString()).equals(handler.getLocalShard())) {
			return false;
		}
		
		for (Filter filter : filters) {
			Object actual = doc.get(filter.getField());
			Object expected = filter.getValue(); 
			Comparison comparison = filter.getComparison();
			
			switch (comparison) {
			case NOT_NULL:
				if (actual == null) {
					return false;
				}
				
				break;
			case NULL:
				if (actual != null) {
					return false;
				}
				
				break;
			case FULLTEXT:
				return true;
			default:
				if (!comparison.matches(actual == null ? DBNull.NULL : actual, expected, expected.getClass())) {
					return false;
				}	
			}
		}
		
		return true;
	}
			
}
