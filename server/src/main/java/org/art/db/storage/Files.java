package org.art.db.storage;

import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.io.FileUtils;
import org.art.db.common.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Files {
		
	@SuppressWarnings("unused")
	private static final Logger logger = LoggerFactory.getLogger(Files.class);
	
	private final static String META_DATA = "%s/meta.dat";
	
	private final static String TABLE_LOG = "%s/%s.log";
	
	private final static String INDEX = "%s/%s.idx";
	
	private final static String DISK_SEGMENT = "%s/%s_%s.seg";
	
	private final static String MEMORY_SEGMENT = "%s.mem";
	
	private final static String TABLE_DIRECTORY = "%s";
	
	private final static String SECURITY = "security.sec";
	
	private final static String TABLE_LOCK = "%s/lock.dat";
	
	public static File createTempFile(String table, String prefix, String suffix) throws IOException {
		File dir = new File(getRoot(), table);
		File tempFile = File.createTempFile(prefix, suffix, dir);
		
		tempFile.deleteOnExit();
		
		return tempFile;
		
	}
	
	public static File getLogRoot() {
		Configuration config = Configuration.getInstance();
		String root;
		
		if (config != null && (root = config.getProperty("org.art.db.logroot")) != null) {
			return new File(root);
		}
		
		return getRoot();
		
	}
	
	public static File getRoot() {
		Configuration config = Configuration.getInstance();
		
		if (config == null) {
			return new File(".dbroot");
		} else {
			return new File(Configuration.getInstance().getProperty("org.art.db.dbroot", "./dbroot"));
		}
		
	}
	
	private static File getFile(String template, Object ...args) {
		
		File file = new File(getRoot(), String.format(template, args));
		
		file.getParentFile().mkdirs();
		
		return file;
	}
	
	private static File getLogFile(String template, Object ...args) {
		
		File file = new File(getLogRoot(), String.format(template, args));
		
		file.getParentFile().mkdirs();
		
		return file;
	}
	
	public static File getMetadata(String table) {
		return getFile(META_DATA, table);
	}
	
	
	public static File getLockFile(String table) {
		return getFile(TABLE_LOCK, table);
	}
	
	
	public static File getTableLog(String table) {
		return getLogFile(TABLE_LOG, table, System.currentTimeMillis());
	}
	
	public static File getIndex(String table, String index) {
		return getFile(INDEX, table, index);
	}
	
	public static File getDiskSegment(String table, int count) {
		return getFile(DISK_SEGMENT, table, System.currentTimeMillis(), count);
	}
	
	public static File getDiskSegment(String table, long time, int count) {
		return getFile(DISK_SEGMENT, table, time, count);
	}
	
	public static File getMemorySegment(String table) {
		return getFile(MEMORY_SEGMENT, table);
	}
	
	public static File getDirectory() {
		return getRoot();
	}
	
	public static File getSecurity() {
		return getFile(SECURITY);
	}
	
	public static List<String> listTables() {
		List<String> result = new ArrayList<>();
		File[] dirs = getRoot().listFiles((x, y) -> new File(x, y).isDirectory());
		
		if (dirs != null) {
			for (File dir : dirs) {
				if (new File(dir, "meta.dat").exists()) {
					result.add(dir.getName());
				}
			}
		}
		
		return result;
	}
	public static File[] listDiskSegments(String table) {
		File tableDir = new File(getRoot(), table);
		
		return tableDir.listFiles((dir, name) -> name.endsWith(".seg"));
	}
	
	public static File[] listTableLogs(String table) {
		File tableDir = new File(getLogRoot(), table);
		
		return tableDir.listFiles((dir, name) -> name.endsWith(".log"));
	}
	
	public static File[] list(String table, FileFilter filter) {
		File tableDir = new File(getRoot(), table);
		return tableDir.listFiles(filter);
	}
	
	public static void remove(String table) throws IOException {
		File dir = getFile(TABLE_DIRECTORY, table);
		
		FileUtils.deleteDirectory(dir);
	}
	
}
