package org.art.query;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.art.storage.Comparison;
import org.art.storage.IndexInfo;
import org.art.db.common.Key;
import org.art.storage.Metadata;
import org.art.db.common.ServerDocumentSet;
import org.art.storage.StorageManager;
import org.art.storage.TableManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Planner {
	@SuppressWarnings("unused")
	private static final Logger logger = LoggerFactory.getLogger(Planner.class);
	
	
	@SuppressWarnings("unchecked")
	public static Plan buildPlan(String table, List<String> selectColumns, List<Filter> filters) throws Exception {
		
		// Build some useful maps
		
		Metadata metadata = Metadata.getMetadata(table);
		List<IndexInfo> indices = metadata.indices();
		List<String> primaryKeys = metadata.primaryKeyFields();
		TableManager manager = StorageManager.getInstance().getTableManager(table);
		
		Map<String, Filter> fieldNameToFilter = new HashMap<>();
		
		boolean equals = true;
		
		for (Filter filter : filters) {
			fieldNameToFilter.put(filter.getField(), filter);
			equals &= filter.getComparison() == Comparison.EQ;
		}
		
		Map<String, IndexInfo> indexNameToInfo = new HashMap<>();
		
		for (IndexInfo index : indices) {
			indexNameToInfo.put(index.name(), index);
		}
		
		// If we are doing an EQ against the leading fields of the primary key
		// then do a table lookup.
		
		Scan primaryKeyScan = getScan("", primaryKeys, fieldNameToFilter);		
				
		if (primaryKeyScan != null && equals) {
			return new  TableLookup(table, filters, primaryKeyScan.getKey());
		}
		
		// If we are doing an IN on the leading field of the primary key then do a 
		// series of lookups against all of the values in the IN clause
		
		for (Filter filter : filters) {
			if (filter.getField().equals(primaryKeys.get(0)) && filter.getComparison() == Comparison.IN) {
				return new TableInLookup(table, filters, (List<Comparable<?>>) filter.getValue());
			}
		}
		
		// Otherwise find all the ways that we could to lookups on alternate indices
		
		List<Scan> alternateKeyLookups = new ArrayList<>();
		
		for (IndexInfo index : indices) {		
			Scan scan = getScan(index.name(), index.fields(), fieldNameToFilter);
			
			if (scan != null) {
				alternateKeyLookups.add(scan);
			}
		}
		
		// If there's a projection list and one of the alternate key lookups covers the projected fields 
		// then do an index scan.
		
		if (selectColumns != null && !selectColumns.isEmpty()) {
			for (Scan scan : alternateKeyLookups) {
				IndexInfo index = indexNameToInfo.get(scan.getIndex());
				boolean covers = true;
				
				for (String name : selectColumns) {
					covers &= index.fields().contains(name);
				}

				if (covers) {
					return new IndexScan(table, filters, selectColumns, scan, index.fields(), scan.getKey());
				}
			}
		}
		
		// See there's an IN on one of the alternate keys then do a lookup on
		// all of the values in the IN clause
		
		for (IndexInfo index : indices) {		
			String firstKey = index.fields().get(0);
			Filter filter = fieldNameToFilter.get(firstKey);
			
			if (filter != null && filter.getComparison() == Comparison.IN) {
				return new IndexInLookup(table, filters, index.name(), (List<Comparable<?>>) filter.getValue());
			}
		}
		
		// See there's a BETWEEN clause on one of the alternate keys then do a lookup on
		// all of the values in the BETWEEN clause

		for (IndexInfo index : indices) {		
			String firstKey = index.fields().get(0);
			Filter filter = fieldNameToFilter.get(firstKey);

			if (filter != null && filter.getComparison() == Comparison.BETWEEN) {
				List<Key> keys = filter.getKeys();
				
				return new IndexBetweenLookup(table, filters, index.name(), keys.get(0), keys.get(1));
			}
		}
		
		// If there are any scans then do an index scan.  If there are multiple
		// scans that match then remove the ones that aren't very selective.
				
		if (!alternateKeyLookups.isEmpty()) {
			
			for (int idx = 0; idx < alternateKeyLookups.size() && alternateKeyLookups.size() > 1;) {
				Scan scan = alternateKeyLookups.get(idx);
				
				if (manager.getSelectivity(scan.getIndex()) > 20) {
					alternateKeyLookups.remove(idx);
				} else {
					idx++;
				}
			}
			
			return new IndexLookup(table, filters, alternateKeyLookups);
		}
		
		throw new Exception("The query does must include one of more indexed fields");
		
	}
	

	private static Scan getScan(String index, List<String> keyFields, Map<String, Filter> nameToFilter) {
		List<Filter> filters = new ArrayList<>();
		
		for (String field : keyFields) {
			Filter filter = nameToFilter.get(field);
			
			if (filter != null) {
				filters.add(filter);
			}
		}
		
		if (filters.isEmpty()) {
			return null;
		}
		
		Filter first = filters.get(0);
		Comparison comp = first.getComparison();
		
		if (comp == Comparison.BETWEEN || comp == Comparison.IN) {
			return null;
		}
		
		if (comp != Comparison.EQ) {
			return new Scan(index, comp, first.getKey());
		}
		
		List<Comparable<?>> keys = new ArrayList<>();
		
		for (Filter filter : filters) {
			if (filter.getComparison() == Comparison.EQ) {
				keys.add((Comparable<?>)filter.getValue());
			}
		}
		
		return new Scan(index, comp, new Key(keys));
	}
	
	static class DummyPlan extends Plan {
		DummyPlan() throws Exception {
			super("$DUMMY$", null);
		}

		@Override
		public ServerDocumentSet execute(TableManager engine) throws Exception { return null; }
	}
	
}
