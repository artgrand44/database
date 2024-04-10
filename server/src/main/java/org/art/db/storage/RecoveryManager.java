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
	
	private void recover(URI peer, String table, URI thisURI) throws Exception {
		TableManager manager = StorageManager.getInstance().getTableManager(table);
		
		File[] files = Files.listDiskSegments(table);
		long timestamp;
		int mergeLevel;
		DiskSegment segment;
		
		if (files == null || files.length == 0) {			
			logger.info("No segments");
			timestamp = 0;
			mergeLevel = 1;
		} else {
			List<File> list = Arrays.asList(files);
			Collections.sort(list, (a, b) -> - a.getName().compareTo(b.getName()));
			
			segment = new DiskSegment(list.get(0));
			timestamp = segment.getCreateTime();
			mergeLevel = segment.getMergeLevel();
		}
		
		File file;
		
		File metadataFile = Files.getMetadata(table);
		Metadata metadata = metadataFile.exists() ? new ObjectMapper().readValue(metadataFile, Metadata.class) : new Metadata(table);
		
		this.restClient.get(restClient.buildURI(peer, "db/recovering?uri=%s&table=%s", thisURI, table));
				
		for (;;) {
			file = Files.getDiskSegment(table, 0);
			
			this.restClient.getFile(restClient.buildURI(peer, "/db/getnextfile?table=%s&timestamp=%s", table, timestamp), file);
			
			if (file.length() == 0) {
				logger.info("Empty file");
				file.delete();
				return;
			}
			
			segment = new DiskSegment(file);
			
			for (Key key : segment.getZombies()) {
				Document doc = manager.get(key);
				
				if (doc != null) {
					logger.info("Remove " + key);
					manager.remove(doc, false);
				}
			}
			
			Iterator<Map.Entry<Key, Blob>> iterator = segment.iterator();
			
			while (iterator.hasNext()) {
				Map.Entry<Key, Blob> entry = iterator.next();
				Document oldDoc = manager.get(entry.getKey());
				Document newDoc = BlobConverter.toDocument(metadata, entry.getValue());
				
				manager.updateIndices(oldDoc, newDoc, entry.getKey());
			}
			
			mergeLevel = segment.getMergeLevel();
			timestamp = segment.getCreateTime();
			
			segment.close();
			
			File renamed = Files.getDiskSegment(table, timestamp, mergeLevel);
			logger.info("processed  " + renamed.getAbsolutePath());
			
			
			
			file.renameTo(renamed);
			
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

}
