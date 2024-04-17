package org.art.db.storage;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.art.db.common.DBRuntimeException;
import org.art.db.common.DiscoveryHandler;
import org.art.db.ui.UIConsole;
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
				UIConsole.getInstance().logAlert("There was an error closing the database");
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
	
	public File getNextFile(String table, long timestamp) {
		File[] files = Files.listDiskSegments(table);
		
		if (files == null) {
			return null;
		}
		
		List<File> list = Arrays.asList(files);
		Collections.sort(list, (a, b) -> a.getName().compareTo(b.getName()));

		for (File file : list) {
			String name = file.getName();
			int dash = name.indexOf('_');
			long fileTimeStamp = Long.parseLong(name.substring(0, dash));
			
			if (fileTimeStamp > timestamp) {
				return file;
			}
		}
		
		return null;
	}


	public TableManager getTableManager(String table) throws Exception {
		return  this.tableManagers.computeIfAbsent(table, x -> this.createTableManager(x));
	}

	public void recover(DiscoveryHandler handler) {
		for (String table : Files.listTables()) {
			try {
				this.getTableManager(table).recover(handler);
			} catch (Exception exc) {
				UIConsole.getInstance().logAlert("There was an error recovering the database");
				logger.info("There was an error recoverting " + table + " " + exc.getMessage());
			}
		}
			
		this.reset();
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
