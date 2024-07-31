package org.art.db.storage;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.art.db.common.DBRuntimeException;
import org.art.db.common.Scheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.vafer.jdeb.shaded.commons.io.FileUtils;

public class BackupHandler {
	
	private static final Logger logger = LoggerFactory.getLogger(BackupHandler.class);
	
	private ScheduledFuture<?> handler;
	
	private final static BackupHandler instance = new BackupHandler();
	
	private final RandomAccessFile lockFile;
	private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
	
	private BackupHandler() {
		try {
			this.lockFile =  new RandomAccessFile(Files.getLockFile("$$backup"), "rw");
		} catch (Exception exc) {
			throw new DBRuntimeException("Error creatinng lock file", exc);
		}
	}
	
	public void backup() {
		FileLock lock = null;
		
		String root = Environment.getInstance().getProperty("org.art.db.backuproot");
		
		if (root == null || root.isEmpty()) {
			return;
		}
		
		try {
			lock = this.lockFile.getChannel().tryLock(0, Long.MAX_VALUE, false);

			this.lock.writeLock().lock();
			
			if (lock != null) {
				File rootFile = new File(root);
				
				this.backupFile(Files.getSecurity(), rootFile);
				
				for (String table : Files.listTables()) {
					this.backupTable(table, rootFile);
				}
			}
		} catch (Exception exc) {
			exc.printStackTrace();
			logger.info("Backup exception " + exc.getMessage());
		} finally {
			this.lock.writeLock().unlock();
			
			if (lock != null) {
				try {
					lock.release();
				} catch (Exception exc) {
					logger.info("Error releasing lock " + exc.getMessage());
				}
			}
		}
	}
	
	private void backupTable(String table, File root) throws Exception  {
		File backupDir = new File(root, table);
		
		backupDir.mkdirs();
		
		StorageManager.getInstance().getTableManager(table).flush();
		
		File[] segments = Files.listDiskSegments(table);
		File[] indices = Files.listFiles(table, ".idx");
		File meta = Files.getMetadata(table);
		
		if (segments != null) {
			for (File segment : segments) {
				File backup = new File(backupDir, segment.getName());
				
				if (!backup.exists()) {
					FileUtils.copyFile(segment, backup);
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

		FileUtils.copyFile(source, backup);
	}
	
	public void endUpdate() {
		this.lock.readLock().unlock();
	}
	
	public static BackupHandler getInstance() {
		return instance;
	}
	
	
	public void start() {
		long interval = new Interval(Environment.getInstance().getPropertyWithDefault("org.art.db.backupinterval")).toMillis();
		
		this.handler = Scheduler.DEFAULT.scheduleAtFixedRate(this::backup, interval, interval, TimeUnit.MILLISECONDS);
	}
	
	public void startUpdate() {
		this.lock.readLock().lock();
	}
	
	public void stop() {
		if (this.handler != null) {
			this.handler.cancel(false);
			this.handler = null;
		}
	}
}
