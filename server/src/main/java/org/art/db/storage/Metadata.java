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
	
	private String name;
	private List<String> primaryKeyFields;
	private SortedMap<String, ColumnInfo> columns;
	private List<IndexInfo> indices;
	boolean isQueue;
	private final transient ReentrantReadWriteLock lock;
	
	private static final Logger logger = LoggerFactory.getLogger(Metadata.class);
	
	private static final ConcurrentMap<String, Metadata> cache = new ConcurrentHashMap<>();
	
	private static final ObjectMapper MAPPER = new ObjectMapper();
	
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
	
	public Metadata() {
		this.lock = new ReentrantReadWriteLock();
	}
	
	public Metadata(Metadata from) {
		this(from.name, new ArrayList<>(from.primaryKeyFields), new TreeMap<>(from.columns), new ArrayList<>(from.indices), from.isQueue);		
	}
	
	public Metadata(String name) {
		this(name, new ArrayList<>(), new TreeMap<>(), new ArrayList<>(), false);
	}
	
	public ColumnInfo column(String name) {
		return columns.get(name);
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
	
	public void removeFromCache() {
		cache.remove(this.name);
	}
	
	public void readLock() throws DBRuntimeException {
		this.lock.readLock().lock();
		
		if (!cache.containsKey(this.name)) {
			this.lock.readLock().unlock();
			throw new DBRuntimeException("Table " + this.name + " does not exist.");
		}
	}
	
	public void writeLock() throws DBRuntimeException {
		this.lock.writeLock().lock();
		
		if (!cache.containsKey(this.name)) {
			this.lock.writeLock().unlock();
			throw new DBRuntimeException("Table " + this.name + " does not exist.");
		}
	}
	
	public void unlock() {
		if (this.lock.isWriteLocked()) {
			this.lock.writeLock().unlock();
		} else {
			this.lock.readLock().unlock();
		}
	}
	
	public static void setMetadata(Metadata meta) throws Exception {
		File file = Files.getMetadata(meta.name);
		
		MAPPER.writeValue(file, meta); 
		cache.put(meta.name, meta);
		
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public List<String> getPrimaryKeyFields() {
		return primaryKeyFields;
	}

	public void setPrimaryKeyFields(List<String> primaryKeyFields) {
		this.primaryKeyFields = primaryKeyFields;
	}

	public SortedMap<String, ColumnInfo> getColumns() {
		return columns;
	}

	public void setColumns(SortedMap<String, ColumnInfo> columns) {
		this.columns = columns;
	}

	public List<IndexInfo> getIndices() {
		return indices;
	}

	public void setIndices(List<IndexInfo> indices) {
		this.indices = indices;
	}

	public boolean isQueue() {
		return isQueue;
	}

	public void setQueue(boolean isQueue) {
		this.isQueue = isQueue;
	}

	@Override
	public String toString() {
		return "Metadata [name=" + name + ", primaryKeyFields=" + primaryKeyFields + ", columns=" + columns
				+ ", indices=" + indices + ", isQueue=" + isQueue + "]";
	}
	
	
}
