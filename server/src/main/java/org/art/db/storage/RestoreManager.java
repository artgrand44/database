package org.art.db.storage;

import java.io.File;
import java.io.FileInputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
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
import org.art.db.common.LogEntry;
import org.art.db.common.RestClient;
import org.art.db.common.Scheduler;
import org.art.db.common.Utils;
import org.art.db.common.LogEntry.Operation;
import org.art.db.security.SecurityHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

public class RestoreManager {
	
	private static final Logger logger = LoggerFactory.getLogger(RestoreManager.class);
	
	private final static RestoreManager instance = new RestoreManager();
	
	private boolean hasMetadata;
	
	private boolean recovered;
	
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
		File localFile = new File(DBFiles.getTableDirectory(table), name);
		
		localFile.delete();		
		this.restClient.getFile(uri, localFile);
		
	}
		
	
	private List<String> getAllFiles(URI peer, String table, String type) throws Exception {
		try {
		List<String> names = this.getFilesNames(peer, table, type);

		logger.info("got names " + names);
		for (String name : names) {
			this.getFile(peer, table, name);
		}
		
		return names;	
		} catch (Exception exc) {
			exc.printStackTrace();
			throw exc;
		}
	}
	
	
	private String getNextFile(URI peer, String table, String type, long timestamp) throws Exception {
		URI uri = this.restClient.buildURI(peer, "/db/getnextfile?table=%s&type=%s&timestamp=%s", table, type, timestamp);
		File file = File.createTempFile("temp", ".seg");
		String name = this.restClient.getFile(uri, file);
		
		if (name != null && !name.isEmpty()) {
			int dash = name.indexOf('_');
			
			timestamp = Long.valueOf(name.substring(0, dash)) + 1;
			file.renameTo(new File(DBFiles.getTableDirectory(table) + "/" + name));
			return name;
		} 

		return null;
	}
	/**
	 * Find a suitable partner.  This method iterates over all of the local peers
	 * and does a fast check to make sure that the peer is fully running.  This
	 * is necessary to avoid deadlocks in the case where two peers are starting
	 * at the same time.
	 * @param handler
	 * @return
	 */
	private URI getPartner(DiscoveryHandler handler) {
		RestClient client = new RestClient(SecurityHandler.getInstance().getAdminUser(), 
				                           SecurityHandler.getInstance().getAdminPassword(), 
				                           Duration.ofMillis(200)); 
		
		for (URI partner : handler.getLocalPeers()) {
			try {
				client.get(client.buildURI(partner, "/db/ping"));
				return partner;
			} catch (Exception exc) {
	
			}
		}
		
		return null;
	}
	
	public void recover(DiscoveryHandler handler) {	
		
		// Determine whether we need to restore the entire database from the backup.
		// We do a restore if the root is empty and a backup is available.
		
		File root = DBFiles.getRoot();
		File[] tableDirs = root.listFiles();
		File backupDirectory = this.getBackupDirectory();
		boolean useBackup = (tableDirs == null || tableDirs.length == 0) && backupDirectory != null;
		
		// Select a peer to work with
		
		URI peer = getPartner(handler);
		boolean newShard = false;
		
		if (peer == null && handler.getShards().size() > 1) {
			peer = handler.getSourceShard(handler.getLocalShard());
			newShard = true;
			logger.info("no peers got source local shard = " +  handler.getLocalShard() + " peer " + peer);
		} else {
			logger.info("using peer " + peer);
		}
						
		if (peer != null) {			
			try {			
				
				// Tell the other instances to start sending us logs
				
				logger.info("sending begin from " +  DiscoveryHandler.getServerInstance().getLocalInstance());
				Broadcaster.getInstance().broadcast("/db/beginstartup?instance=" + DiscoveryHandler.getServerInstance().getLocalInstance(), new byte[0]);
				
				logger.info(DiscoveryHandler.getServerName() + " after sending begin");
								
				// Recover the configuration and the metadata then recover the tables one by one.

				this.recoverConfiguration(peer);
				
				logger.info(DiscoveryHandler.getServerName() + " after getting config");
				this.recoverMetadata(peer);		

				logger.info(DiscoveryHandler.getServerName() + " after getting metadata");
				this.hasMetadata = true;

				// Recovery the tables

				for (String table : DBFiles.listTables()) {
					logger.info("recovering " + table);
					try {
						if (useBackup) {
							logger.info("Restoring " + table + " from backup");
							Files.copy(new File(backupDirectory, table).toPath(), new File(root, table).toPath(), StandardCopyOption.REPLACE_EXISTING);
						}

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
						MetricsLogger.getInstance().logAlert("There was an error recovering the database");
						exc.printStackTrace();
						logger.error("There was an error recoverting " + table + " " + exc.getMessage());
					}
				}
			} catch (Exception exc) {
				MetricsLogger.getInstance().logAlert("There was an exception restoring the backup.");
				logger.error("There was an error synchronizing the configuration " + exc.getMessage());
			}
		} else if (useBackup) {
			try {
				logger.info("Restoring database from backup");
				Files.copy(root.toPath(), backupDirectory.toPath(), StandardCopyOption.REPLACE_EXISTING);
			} catch (Exception exc) {
				logger.error("There was an exception restoring the backup " + exc.getMessage());
			}
		}

		logger.info("set recovered");
		this.recovered = true;
	}
	
	@SuppressWarnings("unchecked")
	private void recoverConfiguration(URI peer) throws Exception {
		RestClient client = new RestClient();
		byte[] bytes = client.get(client.buildURI(peer, "/db/getproperties?source=%s", Environment.getInstance().getSource()));
		
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
	private void recover(TableManager manager, Key key, Document doc, long timestamp, AtomicInteger inProgress) {
		
		try {
			LogEntry entry = new LogEntry(doc.isZombie() ? Operation.REMOVE : Operation.UPSERT, key, doc, timestamp, false);
			
			manager.applySnapshot(entry, true);
		} catch (Exception exc) {
			if (first) {
				MetricsLogger.getInstance().logAlert("There was an error recovering the indices");
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
		
		Metadata.clear();
		
		if (!tables.isEmpty()) {
			RestClient restClient = new RestClient();

			for (String table : tables.split(",")) {
				File file = DBFiles.getMetadata(table);

				restClient.getFile(restClient.buildURI(peer, "/db/getmetadata?table=%s", table, 0), file);
			}		
		}	
	}

	
	private void recoverQueue(TableManager manager, URI peer, String tableName) throws Exception { 
		for (String name : this.getAllFiles(peer, tableName, ".log")) {
			File localFile = new File(DBFiles.getTableDirectory(tableName), name);
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
		
		// Find the timestamp of the most recent segment on this node.
		
		File[] files = DBFiles.listDiskSegments(tableName);
		long timestamp;
		
		if (files == null || files.length == 0) {			
			timestamp = 0;
		} else {
			List<File> list = Arrays.asList(files);
			Collections.sort(list, (a, b) -> - a.getName().compareTo(b.getName()));
			DiskSegment segment = new DiskSegment(list.get(0));
			timestamp = segment.getCreateTime();;
		}		
		
		boolean save = manager.isCheckConstraints();
		
		manager.setCheckConstraints(false);
		
		try {
			
			// Process all of the more recent segments on the peer until we run out of segments
			// or get caught up within a second.
			
			String name;
			
			logger.info("getting files from " + peer);
			
			while ((name = this.getNextFile(peer, tableName, ".seg", timestamp)) != null) {
				File localFile = new File(DBFiles.getTableDirectory(tableName), name);
				DiskSegment segment = new DiskSegment(localFile);

				logger.info("got file " + localFile);
				// Iterate over the segment, find each document, and re-apply it.

				Iterator<Map.Entry<Key, Blob>> iterator = segment.iterator();
				AtomicInteger inProgress = new AtomicInteger();

				while (iterator.hasNext()) {
					Map.Entry<Key, Blob> entry = iterator.next();
					Key key = entry.getKey();
					Blob blob = entry.getValue();
					Document doc = BlobConverter.toDocument(manager.getMetadata(), blob);

					inProgress.incrementAndGet();
					Scheduler.DEFAULT.submit(() -> this.recover(manager, key, doc, segment.getCreateTime(), inProgress));
				}

				while (inProgress.get() != 0) {
					Utils.sleepSafely(10);
				}
				
				timestamp = segment.getCreateTime();
				segment.close();
				Utils.sleepSafely(5000);				
			} 
		} finally {
			manager.setCheckConstraints(save);
		}

	}
	
	
	public static RestoreManager getInstance() {
		return instance;
	}


	public boolean hasMetadata() {
		return hasMetadata;
	}


	public boolean isRecovered() {
		return recovered;
	}

}
