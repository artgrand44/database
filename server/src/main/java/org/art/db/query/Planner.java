package org.art.db.query;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import org.art.db.common.Comparison;
import org.art.db.common.DBRuntimeException;
import org.art.db.common.DocumentSet;
import org.art.db.common.Filter;
import org.art.db.common.Key;
import org.art.db.storage.IndexInfo;
import org.art.db.storage.Metadata;
import org.art.db.storage.StorageManager;
import org.art.db.storage.TableManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Planner {
	@SuppressWarnings("unused")
	private static final Logger logger = LoggerFactory.getLogger(Planner.class);
	
	
	@SuppressWarnings("unchecked")
	public static Plan buildPlan(String table, List<String> selectColumns, List<Filter> filters) throws Exception {
		
		// Build some useful maps
		
		Metadata metadata = Metadata.getMetadata(table);
		List<IndexInfo> indices = metadata.getIndices().stream().filter(x -> x.isBuilt() && !x.isFulltext()).toList();
		List<String> primaryKeys = metadata.getPrimaryKeyFields();
		TableManager manager = StorageManager.getInstance().getTableManager(table);
		
		boolean equals = filters.stream().allMatch(x -> x.getComparison() == Comparison.EQ);

		// If we are doing an EQ against the leading fields of the primary key
		// then do a table lookup.
		
		Scan primaryKeyScan = getScan("", primaryKeys, filters);		
				
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
		
		for (IndexInfo info : indices) {		
			Scan scan = getScan(info.getName(), info.getFields(), filters);
			
			if (scan != null) {
				alternateKeyLookups.add(scan);
			}
		}
		
		// If there's a projection list and one of the alternate key lookups covers the projected fields 
		// then do an index scan.
		
		if (selectColumns != null && !selectColumns.isEmpty()) {
			for (Scan scan : alternateKeyLookups) {
				IndexInfo info = metadata.getIndex(scan.getIndex());
				boolean covers = true;
				
				for (String name : selectColumns) {
					covers &= info.getFields().contains(name);
				}

				if (covers) {
					return new IndexScan(table, filters, selectColumns, scan, info.getFields(), scan.getKey());
				}
			}
		}
		
		// See there's an IN on one of the alternate keys then do a lookup on
		// all of the values in the IN clause
		
		for (IndexInfo info : indices) {		
			String firstKey = info.getFields().get(0);
			Filter filter = findFilter(filters, x -> x.getField().equals(firstKey) && x.getComparison() ==  Comparison.IN);
			
			if (filter != null) {
				return new IndexInLookup(table, filters, info.getName(), (List<Comparable<?>>) filter.getValue());
			}
		}
		
		// See there's a BETWEEN clause on one of the alternate keys then do a lookup on
		// all of the values in the BETWEEN clause

		for (IndexInfo info : indices) {		
			String firstKey = info.getFields().get(0);
			Filter filter = findFilter(filters, x -> x.getField().equals(firstKey) && x.getComparison() ==  Comparison.BETWEEN);

			if (filter != null) {
				List<Key> keys = filter.getKeys();
				
				return new IndexBetweenLookup(table, filters, info.getName(), keys.get(0), keys.get(1));
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
		
		throw new DBRuntimeException("Please provide a normal (not fulltext) index for this query.");
		
	}
	
	private static Filter findFilter(List<Filter> list, Predicate<Filter> test) {
		return list.stream().filter(x -> test.test(x)).findFirst().orElse(null);
	}
	
	private static Scan getScan(String index, List<String> keyFields, List<Filter> allFilters) {
		List<Filter> filters = allFilters.stream().filter(x -> keyFields.contains(x.getField())).toList();
		
		if (filters.isEmpty()) {
			return null;
		}
		
		Filter first = filters.get(0);
		Comparison comp = first.getComparison();
		
		if (comp == Comparison.BETWEEN || comp == Comparison.IN || comp == Comparison.NOT_NULL || comp == Comparison.NULL) {
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
		public DocumentSet execute(TableManager engine, long limit, boolean lock) throws Exception { return null; }
	}
	
}
