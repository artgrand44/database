package org.art.db.storage;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.art.db.common.DiscoveryHandler;
import org.art.db.common.Scheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.vafer.jdeb.shaded.commons.io.FileUtils;

public class BackupHandler {
	
	private static final Logger logger = LoggerFactory.getLogger(BackupHandler.class);
	
	private ScheduledFuture<?> handler;
	
	private final static BackupHandler instance = new BackupHandler();
	
	private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
	
	private static final long ONE_DAY = 1000 * 60 * 60 * 24;
	
	private BackupHandler() {
	
	}
	
	public void backup() {
		
		// Get the property for the backup root.  If the root is not specified
		// then assume that backups are disabled.
		
		String root = Environment.getInstance().getProperty("org.art.db.backuproot");
		
		if (root == null || root.isEmpty()) {
			return;
		}
		
		// Generate a directory name for this backup using the form xxx-yyy where xxx is the
		// system time in millis and and yyy is the host name.
		
		File rootFile = new File(root);
		File backupDir  = this.getBackupDirectory(rootFile);
		
		// Use the directory name to see if we have already started a backup on another instance.
		// This prevents us from doing two backups at the same time (and thus putting two nodes
		// out of commission at the same time).
		
		if (this.alreadyBackedupToday(backupDir)) {
			logger.info("Already backed up");
			return;
		}
		
		try {
			this.lock.writeLock().lock();
			
			rootFile.mkdirs();
			this.deleteOldBackups(rootFile);

			this.backupFile(Files.getSecurity(), backupDir);

			for (String table : Files.listTables()) {
				this.backupTable(table, backupDir);
			}
		} catch (Exception exc) {
			exc.printStackTrace();
			logger.info("Backup exception " + exc.getMessage());
		} finally {
			this.lock.writeLock().unlock();
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
			logger.info("There was an error deleting the old backups " + exc.getMessage());
		}
		
	}
	
	public void endUpdate() {
		this.lock.readLock().unlock();		
	}
	
	public static BackupHandler getInstance() {
		return instance;
	}
	
	private File getBackupDirectory(File rootFile) {
		long start = System.currentTimeMillis();
		String instance = DiscoveryHandler.getServerInstance().getLocalInstance().toString();
		
		File backupDir = new File(rootFile, String.valueOf(start) + "_" + instance);

		backupDir.mkdir();
		
		return backupDir;
	}
	
	private long getTimeFromFileName(String file) {
		String[] tokens = file.split("_");
		
		return tokens.length == 2 ? Long.parseLong(tokens[0]) : 0;
	}
	
	private String getInstanceFromFileName(String file) {
		String[] tokens = file.split("_");
		
		return tokens.length == 2 ? tokens[1] : "";
	}
	
	/**
	 * See if we are the the database has already been backed up by another instance
	 * @param backupDir
	 * @return
	 */
	private boolean alreadyBackedupToday(File backupDir) {
		long time = this.getTimeFromFileName(backupDir.getName());
		Calendar calendar = new Calendar.Builder().setInstant(time).build();
		String instance = this.getInstanceFromFileName(backupDir.getName());
		File[] files = backupDir.getParentFile().listFiles(x -> !x.equals(backupDir));
		
		if (files != null) {
			List<File> list = Arrays.asList(files);
				
			Collections.sort(list, (x, y) -> x.getName().compareTo(y.getName()));

			for (File file : list) {
				String name = file.getName();

				try {
					Calendar fileCalendar = new Calendar.Builder().setInstant(this.getTimeFromFileName(name)).build();
					String fileInstance = getInstanceFromFileName(name);

					if (calendar.get(Calendar.YEAR) == fileCalendar.get(Calendar.YEAR)  && calendar.get(Calendar.DAY_OF_YEAR) == fileCalendar.get(Calendar.DAY_OF_YEAR)) {
						if (calendar.after(fileCalendar)) {
							return true;
						}

						if (calendar.compareTo(fileCalendar) == 0 && instance.compareTo(fileInstance) > 0) {
							return true;					
						}
					}

				} catch (Exception exc) {

				}

			}
		}
		
		return false;
	}
	
	public void start() {
		long interval = Math.max(ONE_DAY, new Interval(Environment.getInstance().getPropertyWithDefault("org.art.db.backupinterval")).toMillis());
		
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
