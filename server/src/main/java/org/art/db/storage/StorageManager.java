package org.art.db.storage;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.art.db.common.DBRuntimeException;
import org.art.db.common.Utils;
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
			Utils.closeSafely(entry.getValue());			
		}
		
		this.tableManagers.clear();
		
	}
	
	public void closeTable(String table) throws IOException {
		TableManager manager = this.tableManagers.remove(table);
		
		if (manager != null) {
			manager.close();
		}
	}
	
	public void flushAll()  {		
		for (TableManager manager : this.tableManagers.values()) {
			manager.flush();			
		}	
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
			exc.printStackTrace();
			throw new DBRuntimeException("Error initializing table", exc);
		}
	}
	
	public File getNextFile(String table, long timestamp, String type) {
		
		File[] files = DBFiles.listFiles(table, type);
		
		if (files == null) {
			logger.info("no files for " + type);
			return null;
		}
		
		List<File> list = Arrays.asList(files);
		Collections.sort(list, (a, b) -> a.getName().compareTo(b.getName()));

		for (File file : list) {
			long fileTimeStamp = DBFiles.getTimestampFromFile(file.getName());
			
			if (fileTimeStamp > timestamp) {
				return file;
			} 
		}
		
		return null;	
	}


	public TableManager getTableManager(String table) throws DBRuntimeException {
		return this.tableManagers.computeIfAbsent(table, x -> this.createTableManager(x));
	}
	
	public void reset() {
		for (TableManager manager : this.tableManagers.values()) {
			try {
				manager.close();
			} catch (Exception exc) {
				logger.info("There was an error closing the table " + exc);
			}
		}
		
		this.tableManagers.clear();
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
