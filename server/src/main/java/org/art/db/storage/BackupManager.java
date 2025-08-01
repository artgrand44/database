package org.art.db.storage;

import java.io.File;
import java.net.InetAddress;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.apache.commons.io.FileUtils;
import org.art.db.common.DiscoveryHandler;
import org.art.db.common.Scheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BackupManager {
	
	private static final Logger logger = LoggerFactory.getLogger(BackupManager.class);
	
	private ScheduledFuture<?> handler;
	private final String localHost;
	private final long interval;
	private final long startTime;
	
	private final static BackupManager instance = new BackupManager();
	
	private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
	
	
	private static final long ONE_DAY = 1000 * 60 * 60 * 24;
	private static final String COMPLETION_FILE = "/completion.dat";
	
	private BackupManager() {
		try {
			this.localHost = InetAddress.getLocalHost().getHostAddress();
			this.interval = Math.max(ONE_DAY, new Interval(Environment.getInstance().getPropertyWithDefault("org.art.db.backupinterval")).toMillis());
			this.startTime = System.currentTimeMillis();
		} catch (Exception exc) {
			throw new IllegalStateException(exc);
		}
	}
	
	public void backup() {
		try {
			DiscoveryHandler.getServerInstance().pausePublishing();
			this.lock.writeLock().lock();
			
			// Generate a directory name for this backup using the form xxx-yyy where xxx is the
			// system time in millis and and yyy is the host name.

			File rootDir = DBFiles.getBackupRoot();
			File backupDir = this.getBackupDirectory(rootDir);
			
			rootDir.mkdirs();
			this.deleteOldBackups(rootDir);

			if (DBFiles.getSecurity().exists()) {
				this.backupFile(DBFiles.getSecurity(), backupDir);
			}

			for (String table : DBFiles.listTables()) {
				this.backupTable(table, backupDir);
			}
			
			FileUtils.write(new File(rootDir, COMPLETION_FILE), String.valueOf(System.currentTimeMillis()), Charset.defaultCharset());
		} catch (Exception exc) {
			exc.printStackTrace();
			logger.error("Backup exception " + exc.getMessage());
		} finally {
			this.lock.writeLock().unlock();
			DiscoveryHandler.getServerInstance().resumePublishing();
		}
		
	}
	
	private void backupIfNeeded() {
		File lastBackup = getLatestBackup();
		long lastTime = lastBackup == null ? this.startTime : this.getTimeFromFileName(lastBackup);
		
		if (System.currentTimeMillis() - lastTime > this.interval) {
			this.backup();
		}
	}
	
	private void backupTable(String table, File root) throws Exception  {
		File backupDir = new File(root, table);
		
		backupDir.mkdirs();
		
		StorageManager.getInstance().getTableManager(table).flush();
		
		File[] segments = DBFiles.listDiskSegments(table);
		File[] indices = DBFiles.listFiles(table, ".idx");
		File meta = DBFiles.getMetadata(table);
		
		if (segments != null) {
			for (File segment : segments) {
				File backup = new File(backupDir, segment.getName());
				
				if (!backup.exists()) {
					Files.copy(segment.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
				}
			}
		}
		
		if (indices != null) {
			for (File index : indices) {
				this.backupFile(index, backupDir);
			}
		}
		
		if (meta.exists()) {
			this.backupFile(meta, backupDir);
		}
		
	}
	
	private void backupFile(File source, File backupDir) throws Exception {
		File backup = new File(backupDir, source.getName());

		Files.copy(source.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
	}
	
	private void deleteOldBackups(File rootFile) {
		try {
			File[] subdirs = rootFile.listFiles();
			int maxBackups = Integer.parseInt(Environment.getInstance().getPropertyWithDefault("org.art.db.backupmax"));

			if (subdirs != null && maxBackups > 0) {
				List<File> list = new ArrayList<>(Arrays.asList(subdirs));

				Collections.sort(list, (x, y) -> x.getName().compareTo(y.getName()));

				while (list.size() > maxBackups) {
					logger.info("deleting old " + list.get(0));
					FileUtils.deleteDirectory(list.remove(0));
				}
			}
		} catch (Exception exc) {
			logger.error("There was an error deleting the old backups " + exc.getMessage());
		}
		
	}
	
	public void endUpdate() {
		this.lock.readLock().unlock();		
	}
	
	public static BackupManager getInstance() {
		return instance;
	}
	
	private File getBackupDirectory(File rootFile) {
		File backupDir = new File(rootFile, String.valueOf(System.currentTimeMillis()) + "_" + this.localHost);

		logger.info("Create backup dir " + backupDir.getAbsolutePath());
		
		backupDir.mkdir();
		
		return backupDir;
	}
	
	private long getTimeFromFileName(File file) {
		String[] tokens = file.getName().split("_");
		
		return tokens.length == 2 ? Long.parseLong(tokens[0]) : 0;
	}
	
	static File getLatestBackup() {
		File[] files = DBFiles.getBackupRoot().listFiles((x, y) -> new File(x, y + COMPLETION_FILE).exists());
		
		if (files == null || files.length == 0) {
			return null;
		}
		
		List<File> list = Arrays.asList(files);
		
		Collections.sort(list);
		
		return list.getLast();
	}
	
		
	public void start() {
		long interval = Math.max(ONE_DAY, new Interval(Environment.getInstance().getPropertyWithDefault("org.art.db.backupinterval")).toMillis());
		
		this.handler = Scheduler.DEFAULT.scheduleAtFixedRate(this::backupIfNeeded, interval, interval, TimeUnit.MILLISECONDS);
	}
	
	public boolean startUpdate() {
		return this.lock.readLock().tryLock();
	}
	
	public void stop() {
		if (this.handler != null) {
			this.handler.cancel(false);
			this.handler = null;
		}
	}
}
