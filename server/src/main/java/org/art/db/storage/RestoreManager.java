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
import org.art.db.common.Broadcaster;
import org.art.db.common.DiscoveryHandler;
import org.art.db.common.Document;
import org.art.db.common.Key;
import org.art.db.common.RestClient;
import org.art.db.common.Scheduler;
import org.art.db.common.Utils;
import org.art.db.security.SecurityHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.vafer.jdeb.shaded.commons.io.FileUtils;

import com.fasterxml.jackson.databind.ObjectMapper;

public class RestoreManager {
	
	private static final Logger logger = LoggerFactory.getLogger(RestoreManager.class);
	
	private final static RestoreManager instance = new RestoreManager();
	
	private final RestClient restClient = new RestClient(SecurityHandler.getInstance().getAdminUser(), SecurityHandler.getInstance().getAdminPassword());
	
	private File getBackupDirectory() {
		String path = Environment.getInstance().getPropertyWithDefault("org.art.db.backuproot");
		
		if (path == null || path.isEmpty()) {
			return null;
		}
		
		File rootDir = new File(path);
		File[] subdirs = rootDir.listFiles();
		
		if (subdirs == null || subdirs.length == 0) {
			return null;
		}
		
		List<File> list = new ArrayList<>(Arrays.asList(subdirs));
		
		Collections.sort(list, (x, y) -> - x.getName().compareTo(y.getName()));
		
		return list.get(0);
	}
	
	
	private List<String> getFilesNames(URI peer, String table, String type) throws Exception {
		URI uri = this.restClient.buildURI(peer, "/db/getfilenames?table=%s&type=%s", table, type);
		String[] names = new String(this.restClient.get(uri)).split(",");
		List<String> result = new ArrayList<>();
		
		for (String name : names) {
			if (!name.isBlank()) {
				result.add(name);
			}
		}
		
		Collections.sort(result);
		
		return result;
	}
	
	private void getFile(URI peer, String table, String name) throws Exception {
		URI uri = this.restClient.buildURI(peer, "/db/getfile?table=%s&name=%s", table, name);
		File localFile = new File(Files.getTableDirectory(table), name);
		
		localFile.delete();		
		this.restClient.getFile(uri, localFile);
		
	}
		
	
	private List<String> getAllFiles(URI peer, String table, String type) throws Exception {
		List<String> names = this.getFilesNames(peer, table, type);

		for (String name : names) {
			this.getFile(peer, table, name);
		}
		
		return names;	
	}
	
	private List<String> getAllFilesAfter(URI peer, String table, String type, long timestamp) throws Exception {
		List<String> names = this.getFilesNames(peer, table, type);
		List<String> result = new ArrayList<>();
				
		for (String name : names) {
			int dash = name.indexOf('_');
			
			long fileTimestamp = Long.valueOf(name.substring(0, dash));
			
			if (fileTimestamp > timestamp) {
				this.getFile(peer, table, name);
				result.add(name);
			} 
		}
		
		return result;	
	}
	
	public void recover(DiscoveryHandler handler) {	
		
		// Determine whether we need to restore the entire database from the backup.
		// We do a restore if the root is empty and a backup is available.
		
		File root = Files.getRoot();
		File[] tableDirs = root.listFiles();
		File backupDirectory = this.getBackupDirectory();
		boolean useBackup = (tableDirs == null || tableDirs.length == 0) && backupDirectory != null;
		
		// Select a peer to work with
		
		URI peer = handler.getRandomPeer();
		boolean newShard = false;
		
		if (peer == null && handler.getShards().size() > 1) {
			peer = handler.getSourceShard(handler.getLocalShard());
			newShard = true;
		}
						
		if (peer != null) {
			try {
				
				// Tell the local peers that we are starting the recovery.  This will cause
				// them to save their logs until we're capable of processing them.
				
				String path = String.format("/db/markrecovering?uri=%s&shard=%s", handler.getLocalInstance(), handler.getLocalShard());
				
				Broadcaster.getInstance().broadcast(handler.getLocalPeers(), path, new byte[0]);
												
				// If there's a peer the recover the configuration and the metadata
				// then recover the tables one by one.
				
				this.recoverConfiguration(peer);
				this.recoverMetadata(peer);		
				
				// Recovery the tables
				
				for (String table : Files.listTables()) {
					try {
						if (useBackup) {
							logger.info("Restoring " + table + " from backup");
							FileUtils.copyDirectory(new File(backupDirectory, table), new File(root, table));
						}
						
						logger.info("Recovering " + table + " on " + handler.getLocalInstanceCount());
						
						// Tell the peer to flush its current segment 

						this.restClient.get(restClient.buildURI(peer, "/db/flush?table=%s", table));
						
						// Get the table manager.  This will trigger local recovery for any updates that
						// were lost during the crash.
						
						TableManager manager = StorageManager.getInstance().getTableManager(table);
						
						// Process any recent updates on the partner
						
						if (manager.isQueue()) {
							this.recoverQueue(manager, peer, table);
						} else {
							this.recoverTable(manager, peer, table);
						}
						
						if (newShard) {
							manager.mergeAll();
						}
					} catch (Exception exc) {
						Metrics.getInstance().logAlert("There was an error recovering the database");
						exc.printStackTrace();
						logger.info("There was an error recoverting " + table + " " + exc.getMessage());
					}
				}
					
				StorageManager.getInstance().reset();
			} catch (Exception exc) {
				Metrics.getInstance().logAlert("There was an exception restoring the backup.");
				logger.info("There was an error synchronizing the configuration " + exc.getMessage());
			}
		} else if (useBackup) {
			try {
				logger.info("Restoring database from backup");
				FileUtils.copyDirectory(root, backupDirectory);
			} catch (Exception exc) {
				logger.info("There was an exception restoring the backup " + exc.getMessage());
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
	boolean first = true;
	/**
	 * Update the index entries for a recovered document
	 * @param key
	 * @param blob
	 * @param inProgress
	 */
	private void recoverIndexEntries(TableManager manager, Key key, Blob blob, AtomicInteger inProgress) {
		try {
			Document oldDoc = manager.get(key);
			Document newDoc = BlobConverter.toDocument(manager.getMetadata(), blob);
		
			manager.updateIndices(oldDoc, newDoc, key);
		} catch (Exception exc) {
			if (first) {
				Metrics.getInstance().logAlert("There was an error recovering the indices");
				logger.info("Error recovering index entries " + key + " " + exc.getMessage());
				exc.printStackTrace();
				first = false;
			}
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

				restClient.getFile(restClient.buildURI(peer, "/db/getmetadata?table=%s", table, 0), file);
			}	
		}	
	}

	private void recoverQueue(TableManager manager, URI peer, String tableName) throws Exception { 
		for (String name : this.getAllFiles(peer, tableName, ".log")) {
			File localFile = new File(Files.getTableDirectory(tableName), name);
			boolean save = manager.isCheckConstraints();
			
			manager.setCheckConstraints(false);
			
			try (FileInputStream stream = new FileInputStream(localFile)) {
				manager.applySnapshot(stream, false, localFile);
			} catch (Exception exc) {
				manager.setCheckConstraints(save);
			}
			
		}
	}
	
	@SuppressWarnings("resource")
	private void recoverTable(TableManager manager, URI peer, String tableName) throws Exception { 
		File[] files = Files.listDiskSegments(tableName);
		long timestamp;

		
		if (files == null || files.length == 0) {			
			logger.info("No segments");
			timestamp = 0;
		} else {
			List<File> list = Arrays.asList(files);
			Collections.sort(list, (a, b) -> - a.getName().compareTo(b.getName()));
			DiskSegment segment = new DiskSegment(list.get(0));
			timestamp = segment.getCreateTime();;
		}		
		
		for (String name : this.getAllFilesAfter(peer, tableName, ".seg", timestamp)) {
			File localFile = new File(Files.getTableDirectory(tableName), name);
			DiskSegment segment = new DiskSegment(localFile);

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
				Scheduler.DEFAULT.submit(() -> this.recoverIndexEntries(manager, key, blob, inProgress));
			}

			while (inProgress.get() != 0) {
				Utils.sleepSafely(10);
			}
					
			segment.close();
			
		}

	}
	
	
	public static RestoreManager getInstance() {
		return instance;
	}

}
