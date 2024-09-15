package org.art.db.storage;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.art.db.common.DBRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

public class Metadata  {
	
	private static final Logger logger = LoggerFactory.getLogger(Metadata.class);
	private static final ConcurrentMap<String, Metadata> cache = new ConcurrentHashMap<>();
	private static final ObjectMapper MAPPER = new ObjectMapper();
	private String name;
	private List<String> primaryKeyFields;
	private SortedMap<String, ColumnInfo> columns;
	
	private List<IndexInfo> indices;
	
	boolean isQueue;
	
	private final transient ReentrantReadWriteLock lock;
	
	public Metadata() {
		this.lock = new ReentrantReadWriteLock();
	}
	
	public Metadata(Metadata from) {
		this(from.name, new ArrayList<>(from.primaryKeyFields), new TreeMap<>(from.columns), new ArrayList<>(from.indices), from.isQueue);		
	}
	
	public Metadata(String name) {
		this(name, new ArrayList<>(), new TreeMap<>(), new ArrayList<>(), false);
	}
	
	public Metadata(String name, List<String> primaryKeyFields, SortedMap<String, ColumnInfo> columns, List<IndexInfo> indices, boolean isQueue)  { 
		this.name = name;
		if (name.indexOf('.') == -1 && !name.isEmpty()) {
			logger.info("bad name");
		}
		this.primaryKeyFields = primaryKeyFields;
		this.columns = columns;
		this.indices = indices;
		this.isQueue = isQueue;
		this.lock = new ReentrantReadWriteLock();
		
	}
	
	public ColumnInfo getColumn(String name) {
		return columns.get(name);
	}
	
	
	public SortedMap<String, ColumnInfo> getColumns() {
		return columns;
	}
	
	public IndexInfo getIndex(String name) {
		return this.indices.stream().filter(x -> x.name.equals(name)).findFirst().orElse(null);
	}
	
	public List<IndexInfo> getIndices() {
		return indices;
	}
	
	public String getName() {
		return name;
	}
	
	public List<String> getPrimaryKeyFields() {
		return primaryKeyFields;
	}
	
	public boolean isQueue() {
		return isQueue;
	}
	
	public void readLock() throws DBRuntimeException {
		this.lock.readLock().lock();
		
		if (!cache.containsKey(this.name)) {
			this.lock.readLock().unlock();
			throw new DBRuntimeException("Table " + this.name + " does not exist.");
		}
	}
	
	public void removeFromCache() {
		cache.remove(this.name);
	}
	
	public void setColumns(SortedMap<String, ColumnInfo> columns) {
		this.columns = columns;
	}

	public void setIndices(List<IndexInfo> indices) {
		this.indices = indices;
	}

	public void setName(String name) {
		this.name = name;
	}

	public void setPrimaryKeyFields(List<String> primaryKeyFields) {
		this.primaryKeyFields = primaryKeyFields;
	}

	public void setQueue(boolean isQueue) {
		this.isQueue = isQueue;
	}

	@Override
	public String toString() {
		return "Metadata [name=" + name + ", primaryKeyFields=" + primaryKeyFields + ", columns=" + columns
				+ ", indices=" + indices + ", isQueue=" + isQueue + "]";
	}

	public void unlock() {
		if (this.lock.isWriteLocked()) {
			this.lock.writeLock().unlock();
		} else {
			this.lock.readLock().unlock();
		}
	}

	public void writeLock() throws DBRuntimeException {
		this.lock.writeLock().lock();
		
		if (!cache.containsKey(this.name)) {
			this.lock.writeLock().unlock();
			throw new DBRuntimeException("Table " + this.name + " does not exist.");
		}
	}

	public static void clear() {
		cache.clear();
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
			return null;
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
