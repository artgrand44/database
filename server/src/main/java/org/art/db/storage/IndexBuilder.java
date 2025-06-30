package org.art.db.storage;

import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.art.db.common.Blob;
import org.art.db.common.DBNull;
import org.art.db.common.Document;
import org.art.db.common.Key;
import org.art.db.common.Sorter;
import org.art.db.common.TypedInputStream;
import org.art.db.common.TypedOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class IndexBuilder {

	private static final Logger logger = LoggerFactory.getLogger(IndexBuilder.class);
	
	private static final int SECONDARY = 0;
	private static final int PRIMARY = 1;
	private static final int SEGMENT = 2;
	
	@SuppressWarnings({ "unchecked", "rawtypes" })
	public static void build(Table table, Index index, Metadata metadata) throws Exception {
		IndexInfo info = metadata.getIndex(index.getName());
		File builderFile = DBFiles.getIndexBuilder(metadata.getName(), info.getName());
		File filteredFile = DBFiles.getFilteredIndexBuilder(metadata.getName(), info.getName());
		long totalCount = 0;
		
		// Go through the segments are create a sort file with the secondary key, primary key,
		// and segment number of each document
		
		try (TypedOutputStream stream = new TypedOutputStream(builderFile)) {
			List<Segment> segments = table.getSegments();
			
			for (int idx = 0; idx < segments.size(); idx++) {
				Iterator<Map.Entry<Key, Blob>> iterator = segments.get(idx).iterator();

				while (iterator.hasNext()) {
					Map.Entry<Key, Blob> entry = iterator.next();
					Document doc = BlobConverter.toDocument(metadata, entry.getValue());
					Key primaryKey = entry.getKey();
					Key secondaryKey = buildKey(doc, info.getFields());
					List<Object> sortEntry = new ArrayList<>();
					
					sortEntry.add(secondaryKey);
					sortEntry.add(primaryKey);
					sortEntry.add(idx);
					
					stream.writeObject(sortEntry);	
					totalCount++;
				}
			}
		}
			
		// Sort the file by primary key and version then filter out the duplicates


		Sorter<List> sorter = new Sorter(List.class);

		File sortFile = sorter.sort(builderFile, IndexBuilder::compareByPrimaryAndSegment);

		Key prevKey = null;
		long readCount = 0;
		long filteredCount = 0;

		try (TypedInputStream sortStream = new TypedInputStream(sortFile); TypedOutputStream filteredStream = new TypedOutputStream(filteredFile)) {
			while (readCount++ < totalCount) {
				List<Object> entry = (List<Object>)sortStream.readObject();
				Key key = (Key)entry.get(PRIMARY);

				if (!key.equals(prevKey)) {
					filteredStream.writeObject(entry);
					prevKey = key;
					filteredCount++;
				}
			}
		} 

		// Sort the remaining entries by secondary and primary key then create
		// the index entries

		sortFile = sorter.sort(filteredFile, IndexBuilder::compareBySecondaryAndPrimary);
		readCount = 0;

		try (TypedInputStream sortStream = new TypedInputStream(sortFile)) {
			while (readCount++ < filteredCount) {
				List<Object> entry = (List<Object>)sortStream.readObject();
				Key secoondary = (Key)entry.get(SECONDARY);
				Key primary = (Key)entry.get(PRIMARY);

				index.put(secoondary, primary);					
			}
		}

		info.setBuilt(true);
		metadata.store();
		logger.info("Set built");
	}

	
	@SuppressWarnings("rawtypes")
	private static int compareByPrimaryAndSegment(List list1, List list2) {
		int comp = ((Key)list1.get(PRIMARY)).compareTo(((Key)list2.get(PRIMARY)));
		
		return comp == 0 ? ((Integer)list1.get(SEGMENT)).compareTo( ((Integer)list1.get(SEGMENT))) : comp;
	}
	
	@SuppressWarnings("rawtypes")
	private static int compareBySecondaryAndPrimary(List list1, List list2) {
		int comp = ((Key)list1.get(SECONDARY)).compareTo(((Key)list2.get(SECONDARY)));
		
		return comp == 0 ? ((Key)list1.get(PRIMARY)).compareTo( ((Key)list1.get(PRIMARY))) : comp;
	}
	
	private static Key buildKey(Document doc, List<String> names) {
		List<Comparable<?>> keys = new ArrayList<>();

		for (String name : names) {
			Comparable<?>  value = (Comparable<?>)doc.get(name);

			keys.add(value == null ? DBNull.NULL : value);
		}

		return new Key(keys);

	}

}
