package org.art.db.storage;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.art.db.common.DBRuntimeException;
import org.art.db.common.DiscoveryHandler;
import org.art.db.common.RestClient;
import org.art.db.common.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

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
		
		File[] files = Files.listFiles(table, type);
		
		if (files == null) {
			logger.info("no files for " + type);
			return null;
		}
		
		List<File> list = Arrays.asList(files);
		Collections.sort(list, (a, b) -> a.getName().compareTo(b.getName()));

		for (File file : list) {
			long fileTimeStamp = Files.getTimestampFromFile(file.getName());
			
			if (fileTimeStamp > timestamp) {
				return file;
			} 
		}
		
		return null;	
	}


	public TableManager getTableManager(String table) throws DBRuntimeException {
		return this.tableManagers.computeIfAbsent(table, x -> this.createTableManager(x));
	}

	
	
	@SuppressWarnings("unchecked")
	public void recover(DiscoveryHandler handler) {
		
		URI peer = handler.getRandomPeer();
		
		if (peer != null) {
			try {
				logger.info("Recovering configuration properties");
				
				
				RestClient client = new RestClient();
				byte[] bytes = client.get(client.buildURI(peer, "/db/getproperties"));
				
				Map<String, String> properties = (Map<String, String>)new ObjectMapper().readValue(bytes, Map.class);
				
				for (Map.Entry<String, String> entry : properties.entrySet()) {
					Environment.getInstance().setProperty(entry.getKey(), entry.getValue());
				}
				
				logger.info("Recovering table directories");
				
				String tables = new String(client.get(client.buildURI(peer, "/db/gettables")));
				
				if (!tables.isEmpty()) {
					RestClient restClient = new RestClient();

					for (String table : tables.split(",")) {
						File file = Files.getMetadata(table);

						if (!file.exists()) {
							restClient.getFile(restClient.buildURI(peer, "/db/getmetadata?table=%s", table, 0), file);
						}
					}	
				}
			} catch (Exception exc) {
				Metrics.getInstance().logAlert("There was an error synchronizing the configuration.");
				logger.info("There was an error synchronizing the configuration " + exc.getMessage());
			}
		}
		
		// Recovery the tables
		
		for (String table : Files.listTables()) {
			try {
				logger.info("Recovering " + table);
				this.getTableManager(table).recover(handler);
			} catch (Exception exc) {
				Metrics.getInstance().logAlert("There was an error recovering the database");
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
