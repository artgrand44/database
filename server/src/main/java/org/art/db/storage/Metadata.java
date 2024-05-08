package org.art.db.storage;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.art.db.common.DBRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

public record Metadata(String name, List<String> primaryKeyFields, SortedMap<String, ColumnInfo> columns, List<IndexInfo> indices)  {
	
	@SuppressWarnings("unused")
	private static final Logger logger = LoggerFactory.getLogger(Metadata.class);
	
	private static final ConcurrentMap<String, Metadata> cache = new ConcurrentHashMap<>();
	
	private static final ObjectMapper MAPPER = new ObjectMapper();
	
	public Metadata(Metadata from) {
		this(from.name, new ArrayList<>(from.primaryKeyFields), new TreeMap<>(from.columns), new ArrayList<>(from.indices));		
	}
	
	public Metadata(String name) {
		this(name, new ArrayList<>(), new TreeMap<>(), new ArrayList<>());
	}
	
	public ColumnInfo column(String name) {
		return columns.get(name);
	}
	
	public static Metadata getMetadata(String table) {
		
		if (cache.containsKey(table)) {
			return cache.get(table);
		}
		
		File file = Files.getMetadata(table);
		
		if (file.exists()) {
			Metadata result = load(file);
			
			cache.put(table, result);
			return result;
		} else {
			throw new DBRuntimeException("Table " + table + " does not exist.");
		}
	}
	
	private static Metadata load(File file) {
		try {
			return MAPPER.readValue(file, Metadata.class);
		} catch (Exception exc) {
			throw new IllegalStateException(exc);
		}
	}
	
	public static void setMetadata(Metadata meta) throws Exception {
		File file = Files.getMetadata(meta.name);
		
		MAPPER.writeValue(file, meta); 
		cache.put(meta.name, meta);
		
	}
}
