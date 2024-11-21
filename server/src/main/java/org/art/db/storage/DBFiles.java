package org.art.db.storage;

import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DBFiles {
		
	@SuppressWarnings("unused")
	private static final Logger logger = LoggerFactory.getLogger(DBFiles.class);
	
	private final static String META_DATA = "%s/meta.dat";
	
	private final static String TABLE_LOG = "%s/%s.log";
	
	private final static String INDEX = "%s/%s.idx";
	
	private final static String INVERSE_INDEX = "%s/%s.inv";
	
	private final static String DISK_SEGMENT = "%s/%s_%s.seg";
	
	private final static String MEMORY_SEGMENT = "%s.mem";
	
	private final static String TABLE_DIRECTORY = "%s";
	
	private final static String SECURITY = "security.sec";
	
	private final static String TABLE_LOCK = "%s/lock.dat";
	
	public static File changeExtention(File file, String extension) {
		String path = file.getAbsolutePath();
		int dot = path.lastIndexOf('.');
		
		return new File(path.substring(0,  dot) + extension);
	}
	
	public static File createTempFile(String table, String prefix, String suffix) throws IOException {
		File dir = new File(getRoot(), table);
		File tempFile = File.createTempFile(prefix, suffix, dir);
		
		tempFile.deleteOnExit();
		
		return tempFile;
		
	}
	
	public static File getRoot() {
		Environment config = Environment.getInstance();
		
		if (config == null) {
			return new File(".dbroot");
		} else {
			return new File(Environment.getInstance().getProperty("org.art.db.dbroot", "./dbroot"));
		}
		
	}
	
	public static long getTimestampFromFile(String fileName) {
		String[] tokens = fileName.split("[_.]");
		
		return Long.parseLong(tokens[0]);
	}
	
	private static File getFile(String template, Object ...args) {
		
		File file = new File(getRoot(), String.format(template, args));
		
		file.getParentFile().mkdirs();
		
		return file;
	}
	
	private static File getLogFile(String template, Object ...args) {
		
		File file = new File(getRoot(), String.format(template, args));
		
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
	
	public static File getInverseIndex(String table, String index) {
		return getFile(INVERSE_INDEX, table, index);
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
	
	public static File getTableDirectory(String table) {
		return getFile(TABLE_DIRECTORY, table);
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
	
	public static File[] listFiles(String table, String type) {
		File tableDir = new File(getRoot(), table);
		
		return tableDir.listFiles((dir, name) -> name.endsWith(type));
	}
	
	public static File[] listTableLogs(String table) {
		File tableDir = new File(getRoot(), table);
		
		return tableDir.listFiles((dir, name) -> name.endsWith(".log"));
	}
	
	public static File[] list(String table, FileFilter filter) {
		File tableDir = new File(getRoot(), table);
		return tableDir.listFiles(filter);
	}
	
	
}
