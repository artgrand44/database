package org.art.db.storage;

import java.io.File;
import java.net.InetAddress;
import java.nio.charset.Charset;
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
import org.art.db.common.ZipUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BackupManager {
	
	private static final Logger logger = LoggerFactory.getLogger(BackupManager.class);
	
	private ScheduledFuture<?> handler;
	private ScheduledFuture<?> progressHandler;
	private final File progressFile;
	private final String localHost;
	private final long interval;
	private final long startTime;
	
	private final static BackupManager instance = new BackupManager();
	
	private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
	
	
	private static final long ONE_DAY = 1000 * 60 * 60 * 24;
	private static final String PROGRESS_FILE = "/progress.dat";
	
	private BackupManager() {
		try {
			this.localHost = InetAddress.getLocalHost().getHostAddress();
			this.interval = Math.max(ONE_DAY, new Interval(Environment.getInstance().getPropertyWithDefault("org.art.db.backupinterval")).toMillis());
			this.startTime = System.currentTimeMillis();
			this.progressFile = new File(DBFiles.getBackupRoot(), PROGRESS_FILE);
		} catch (Exception exc) {
			throw new IllegalStateException(exc);
		}
	}
	
	public void backup() {
		try {
			DiscoveryHandler.getServerInstance().pausePublishing();
			this.lock.writeLock().lock();
			
			this.progressHandler = Scheduler.DEFAULT.scheduleAtFixedRate(this::markProgress, 0, 30, TimeUnit.SECONDS);
			
			
			String baseName = System.currentTimeMillis() + "_" + this.localHost;
			File rootDir = DBFiles.getBackupRoot();
			File tempFile  = new File(rootDir, baseName + ".tmp");
			File zipFile = new File(rootDir, baseName + ".zip");

			
			StorageManager.getInstance().flushAll();		
			
			ZipUtils.compressDirectory(DBFiles.getRoot(), tempFile);
			
			if (tempFile.renameTo(zipFile)) {
				logger.info("created zip " + zipFile.getAbsolutePath());
			} else {
				logger.info("Rename failed");
			}
			
			this.deleteOldBackups(rootDir);

			logger.info("backup complete");
		} catch (Exception exc) {
			exc.printStackTrace();
			logger.error("Backup exception " + exc.getMessage());
		} finally {
			this.progressHandler.cancel(true);
			this.progressFile.delete();
			this.lock.writeLock().unlock();
			DiscoveryHandler.getServerInstance().resumePublishing();
		}
		
	}
	
	private void backupIfNeeded() {
		File lastBackup = getLatestBackup();
		long lastBackupTime = lastBackup == null ? this.startTime : this.getTimeFromFileName(lastBackup);
		long lastProgessTime = 0;
		long now = System.currentTimeMillis();
		
		try {
			lastProgessTime = Long.parseLong(FileUtils.readFileToString(this.progressFile, Charset.defaultCharset()));	
		} catch (Exception exc) {
			
		}
		
		// If the backup interval has elapsed and no one else is doing a backup then
		// start one.
		
		if (now - lastBackupTime > this.interval && now - lastProgessTime > 60000) {
			this.backup();
		}
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
	
	private long getTimeFromFileName(File file) {
		String[] tokens = file.getName().split("_");
		
		return tokens.length == 2 ? Long.parseLong(tokens[0]) : 0;
	}
	
	public void endUpdate() {
		this.lock.readLock().unlock();		
	}
	
	public static BackupManager getInstance() {
		return instance;
	}
	
	private File getLatestBackup() {
		File[] match = DBFiles.getBackupRoot().listFiles((x, y) -> y.endsWith(".zip"));
		
		if (match == null || match.length == 0) {
			logger.info("getBackup could not find any files " + Arrays.asList(DBFiles.getBackupRoot().list()));
						
			return null;
		}
		
		List<File> sorted = Arrays.asList(match);
		Collections.sort(sorted);
		
		return sorted.getLast();
		
	}
	
	public void restore() {
		File lastBackup = this.getLatestBackup();
		
		logger.info("Restore found " + lastBackup);
		
		if (lastBackup != null) {
			try {
				ZipUtils.decompressDirectoty(lastBackup, DBFiles.getRoot());
			} catch (Exception exc) {
				logger.info("There was an error reloading the backup from " + lastBackup.getAbsolutePath());
			}
		}		
	}
	
	private void markProgress() {
		try {
			FileUtils.write(this.progressFile, String.valueOf(System.currentTimeMillis()), Charset.defaultCharset());
		} catch (Exception exc) {
			logger.info("Exception updating progress file " + exc.getMessage());
		}
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
