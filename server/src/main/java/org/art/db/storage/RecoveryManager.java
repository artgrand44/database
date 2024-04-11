package org.art.db.storage;

import java.io.File;
import java.net.URI;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.art.db.common.Blob;
import org.art.db.common.Configuration;
import org.art.db.common.DiscoveryHandler;
import org.art.db.common.Document;
import org.art.db.common.Key;
import org.art.db.common.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

public class RecoveryManager {
	
	private static final Logger logger = LoggerFactory.getLogger(RecoveryManager.class);
	
	private static final RecoveryManager instance = new RecoveryManager();
	
	private final RestClient restClient = new RestClient();
	
	
	
	public static RecoveryManager getInstance() {
		return instance;
	}
	
	public void recover(DiscoveryHandler handler) {
		List<URI> peers = handler.getLocalPeers();
			
		if (peers.isEmpty()) {
			logger.info("No peers");
			return;
		}
		
		for (String table : Files.listTables()) {
			try {
				this.recover(peers.get(0), table, handler.getLocalInstance());
			} catch (Exception exc) {
				logger.info("There was an error recoverting " + table + " " + exc.getMessage());
			}
		}
			
		StorageManager.getInstance().reset();
	}
	
	private void recover(URI peer, String tableName, URI thisURI) throws Exception {
		TableManager manager = StorageManager.getInstance().getTableManager(tableName);
		
		manager.recover(peer, thisURI);	
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

}
