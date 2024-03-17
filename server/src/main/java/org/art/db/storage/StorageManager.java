package org.art.db.storage;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class StorageManager  {
	private static final Logger logger = LoggerFactory.getLogger(StorageManager.class);
			
	private ConcurrentMap<String, TableManager> tableManagers = new ConcurrentHashMap<>();
	
	private final static StorageManager instance = new StorageManager();
	
	
	public StorageManager() {
	}
	
	public void closeAll()  {
		
		for (Map.Entry<String, TableManager> entry : this.tableManagers.entrySet()) {
			try {
				entry.getValue().close();
			} catch (Exception exc) {
				logger.info("There was an error closing " + entry.getKey() + " " + exc.getMessage());
			}
		}
		
		this.tableManagers.clear();
		
	}
	
	public static StorageManager getInstance() {
		return instance; 
	}
	
	private TableManager createTableManager(String table) {
		try {
			return new TableManager(table);
		} catch (DBRuntimeException exc) {
			throw exc;
		} catch (Exception exc) {
			throw new DBRuntimeException("Error initializing table", exc);
		}
	}
	
	public TableManager getTableManager(String table) throws Exception {
		return  this.tableManagers.computeIfAbsent(table, x -> this.createTableManager(x));
	}

	
	public void stop() {
		for (TableManager manager : this.tableManagers.values()) {
			try {
				manager.close();
			} catch (Exception exc) {
				logger.info("There was an error closing the table " + exc);
			}
		}
	}
	
	
}
