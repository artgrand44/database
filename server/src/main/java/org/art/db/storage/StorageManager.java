package org.art.db.storage;

import java.io.File;
import java.net.URI;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.art.db.common.Blob;
import org.art.db.common.Configuration;
import org.art.db.common.DBRuntimeException;
import org.art.db.common.DiscoveryHandler;
import org.art.db.common.Document;
import org.art.db.common.Key;
import org.art.db.common.RestClient;
import org.checkerframework.checker.units.qual.t;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

public class StorageManager  {
	private static final Logger logger = LoggerFactory.getLogger(StorageManager.class);
			
	private ConcurrentMap<String, TableManager> tableManagers = new ConcurrentHashMap<>();
	
	private final static StorageManager instance = new StorageManager();
	
	private final RestClient restClient = new RestClient();
	
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

	
	public void recover() {
		DiscoveryHandler handler = DiscoveryHandler.getHandler(Configuration.getInstance());
		List<URI> peers = handler.getLocalPeers();
		
		if (peers.isEmpty()) {
			return;
		}
		
		for (String table : Files.listTables()) {
			try {
				this.recover(peers.get(0), table);
			} catch (Exception exc) {
				logger.info("There was an error recoverting " + table + " " + exc.getMessage());
			}
		}
			
	}
	
	public void recover(URI peer, String table) throws Exception {
		TableManager manager = this.getTableManager(table);
		
		File[] files = Files.listDiskSegments(table);
		
		if (files == null) {
			return;
		}
		
		List<File> list = Arrays.asList(files);
		Collections.sort(list, (a, b) -> - a.getName().compareTo(b.getName()));

		
		DiskSegment segment = new DiskSegment(list.get(1));
		long timestamp = segment.getCreateTime();
		int mergeLevel;
		File file;
		
		File metadataFile = Files.getMetadata(table);
		Metadata metadata = metadataFile.exists() ? new ObjectMapper().readValue(metadataFile, Metadata.class) : new Metadata(table);
				
		while ((file = this.copyOldestSegment(peer, table, timestamp)) != null) {
			segment = new DiskSegment(file);
			
			for (Key key : segment.getZombies()) {
				Document doc = manager.get(key);
				
				if (doc != null) {
					manager.remove(doc, false);
				}
			}
			
			Iterator<Map.Entry<Key, Blob>> iterator = segment.iterator();
			
			while (iterator.hasNext()) {
				Map.Entry<Key, Blob> entry = iterator.next();
				Document oldDoc = manager.get(entry.getKey());
				Document newDoc = BlobConverter.toDocument(metadata, entry.getValue());
				
				if (oldDoc == null) {
					manager.insert(newDoc, false);
				} else {
					manager.update(oldDoc, newDoc, false);
				}
			}
			
			mergeLevel = segment.getMergeLevel();
			timestamp = segment.getCreateTime();
			
			segment.close();
			
			file.renameTo(Files.getDiskSegment(table, timestamp, mergeLevel));
		}		
		
	}
	
	private File copyOldestSegment(URI peer, String table, long timestamp) throws Exception {
		File file = File.createTempFile("seg", ".seg");
		
		restClient.getFile(restClient.buildURI(peer, "/db/getfile?table=%s&timestamp=%s", table, timestamp), file);
		
		return file;

	}
		
	public File getOldestFile(String table, long timestamp) {
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
