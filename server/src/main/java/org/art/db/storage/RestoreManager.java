package org.art.db.storage;

import java.io.File;
import java.io.FileInputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.art.db.common.Blob;
import org.art.db.common.DiscoveryHandler;
import org.art.db.common.Document;
import org.art.db.common.Key;
import org.art.db.common.RestClient;
import org.art.db.common.RestException;
import org.art.db.common.Scheduler;
import org.art.db.common.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

public class RestoreManager {
	
	private static final Logger logger = LoggerFactory.getLogger(RestoreManager.class);
	
	private final static RestoreManager instance = new RestoreManager();
	
	private final RestClient restClient = new RestClient();

	private String getNextRemoteFile(URI peer, File localFile, String table, String type, long timestamp) throws Exception{
		URI uri = this.restClient.buildURI(peer, "/db/getnextfile?table=%s&type=%s&timestamp=%s", table, type, timestamp);
		
		return this.restClient.getFile(uri, localFile);
	}
	
	public void recover(DiscoveryHandler handler) {		
		URI peer = handler.getRandomPeer();
		
		if (peer != null) {
			try {
				this.recoverConfiguration(peer);
				this.recoverMetadata(peer);		
				
				// Recovery the tables
				
				for (String table : Files.listTables()) {
					try {
						logger.info("Recovering " + table);
						
						this.recoverTable(table, handler);
					} catch (Exception exc) {
						Metrics.getInstance().logAlert("There was an error recovering the database");
						logger.info("There was an error recoverting " + table + " " + exc.getMessage());
					}
				}
					
				StorageManager.getInstance().reset();
			} catch (Exception exc) {
				Metrics.getInstance().logAlert("There was an error synchronizing the configuration.");
				logger.info("There was an error synchronizing the configuration " + exc.getMessage());
			}
		}		
	
	}
	
	@SuppressWarnings("unchecked")
	private void recoverConfiguration(URI peer) throws Exception {
		RestClient client = new RestClient();
		byte[] bytes = client.get(client.buildURI(peer, "/db/getproperties"));
		
		Map<String, String> properties = (Map<String, String>)new ObjectMapper().readValue(bytes, Map.class);
		
		for (Map.Entry<String, String> entry : properties.entrySet()) {
			Environment.getInstance().setProperty(entry.getKey(), entry.getValue());
		}
		
	}
	
	/**
	 * Update the index entries for a recovered document
	 * @param key
	 * @param blob
	 * @param inProgress
	 */
	private void recoverIndexEntries(TableManager manager, Metadata metadata, Key key, Blob blob, AtomicInteger inProgress) {
		try {
			Document oldDoc = manager.get(key);
			Document newDoc = BlobConverter.toDocument(metadata, blob);
		
			manager.updateIndices(oldDoc, newDoc, key);
		} catch (Exception exc) {
			Metrics.getInstance().logAlert("There was an error recovering the indices");
			logger.info("Error recovering index entries " + key + " " + exc.getMessage());
		} finally {
			inProgress.decrementAndGet();
		}
	}
	
	private void recoverMetadata(URI peer) throws Exception {
		RestClient client = new RestClient();
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
	}

	private void recoverQueueLog(TableManager manager, File downloadFile, String remoteFileName, List<File> filesCreated) throws Exception {
		
		logger.info("got file");
		
		boolean save = manager.isCheckConstraints();
		
		manager.setCheckConstraints(false);
		
		try (FileInputStream stream = new FileInputStream(downloadFile)) {
			manager.applySnapshot(stream, false, downloadFile);
		} catch (Exception exc) {
			manager.setCheckConstraints(save);
		}
		
		File renamed = new File(downloadFile.getParentFile(), remoteFileName);
		
		downloadFile.renameTo(renamed);
		filesCreated.add(renamed);
		
		logger.info("processed  queue" + downloadFile.getAbsolutePath() + " " + renamed.getAbsolutePath());
				
	}
	
	private void recoverSegment(TableManager manager, Metadata metadata, File downloadFile, String remoteFileName, List<File> filesCreated) throws Exception {
		DiskSegment segment = new DiskSegment(downloadFile);

		// Iterate over the segments and update the index entries for each
		// document.  This process is i/o intensive, so we process several
		// documents in parallel.

		Iterator<Map.Entry<Key, Blob>> iterator = segment.iterator();
		AtomicInteger inProgress = new AtomicInteger();

		while (iterator.hasNext()) {
			Map.Entry<Key, Blob> entry = iterator.next();
			Key key = entry.getKey();
			Blob blob = entry.getValue();

			inProgress.incrementAndGet();
			Scheduler.DEFAULT.submit(() -> this.recoverIndexEntries(manager, metadata, key, blob, inProgress));
		}

		while (inProgress.get() != 0) {
			Utils.sleepSafely(10);
		}
				
		segment.close();
		
		File renamed = new File(downloadFile.getParentFile(), remoteFileName);
		
		downloadFile.renameTo(renamed);
		filesCreated.add(renamed);
		
		logger.info("processed  " + renamed.getAbsolutePath());
		
	}
	
	/**
	 * Recover the table after a restore.  The recovery process
	 * copies the missing segments from another instance and then
	 * updates the indices to reflect the missing segments.
	 * 
	 * @param peer The peer to copy the segments from
	 * @param thisURI The URI of this instance
	 * @throws Exception
	 */
	public void recoverTable(String tableName, DiscoveryHandler handler) throws Exception {
		Metadata metadata = Metadata.getMetadata(tableName);
		TableManager manager = StorageManager.getInstance().getTableManager(tableName);
		long timestamp;
		
		
		if (metadata.isQueue()) {
			timestamp = 0;
		} else {
			File[] files = Files.listDiskSegments(tableName);

			DiskSegment segment;

			// Find the timestamp and merge level for the newest segment on this
			// server

			if (files == null || files.length == 0) {			
				logger.info("No segments");
				timestamp = 0;
			} else {
				List<File> list = Arrays.asList(files);
				Collections.sort(list, (a, b) -> - a.getName().compareTo(b.getName()));

				segment = new DiskSegment(list.get(0));
				timestamp = segment.getCreateTime();;
			}		
		}
		
		// Pick a random instance in this shard and try to copy its segments.
		// We try up to 10 instances before giving up.  If an instance goes
		// down in the middle of a recovery, we delete all the newly recovered 
		// segments and try again.
		
		List<File> filesCreated = new ArrayList<>();
		File downloadFile = null;
		URI peer = null;
		
		for (int idx = 0; idx < 10; idx++) {
			try {				
				peer = handler.getRandomPeer();
				String type;
				
				if (peer == null) {
					return;
				}
				
				if (metadata.isQueue()) {
					downloadFile = Files.getTableLog(tableName);
					type = ".log";
				} else {
					downloadFile = Files.getDiskSegment(tableName, 0);
					type = ".seg";
				}
				
				// Tell the peer to flush its current segment and to begin sending broadcasts to this
				// instance.  The peer will continue sending the broadcasts until this instance is up
				// and running.

				this.restClient.get(restClient.buildURI(peer, "/db/recovering?uri=%s&table=%s", handler.getLocalInstance(), tableName));
				
				// Get the latest version of the metadata

				this.restClient.getFile(restClient.buildURI(peer, "/db/getmetadata?table=%s", tableName, timestamp), Files.getMetadata(tableName));

				// Process the missing logs/segments from oldest to newest.  Continue until we get an empty file.
				// The empty file acts as a "no more segments" indicator.
			
				for (;;) {
					String remoteFile = this.getNextRemoteFile(peer, downloadFile, tableName, type, timestamp);
					
					if (downloadFile.length() == 0) {
						downloadFile.delete();
						return;
					}
					
					if (metadata.isQueue()) {
						this.recoverQueueLog(manager, downloadFile, remoteFile, filesCreated);
					} else {
						this.recoverSegment(manager, metadata, downloadFile, remoteFile, filesCreated);
					}
					
					timestamp = Files.getTimestampFromFile(remoteFile);
				}				
			} catch (RestException exc) {
				for (File file : filesCreated) {
					file.delete();
				}
				
				if (exc.getStatus() == RestException.NO_CONNECTION || exc.getStatus() == RestException.SERVER_DOWN) {
					logger.info("doCall got fail on " + peer + " exc " + exc);
					handler.removeInstance(peer);
				} else {
					throw exc;
				}
			} finally {
				if (downloadFile != null) {
					downloadFile.delete();
				}
			}

		}		
		
	}
	
	public static RestoreManager getInstance() {
		return instance;
	}

}
