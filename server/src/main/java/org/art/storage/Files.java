package org.art.storage;

import java.io.File;
import java.io.FileFilter;
import java.io.IOException;

import org.apache.commons.io.FileUtils;
import org.art.db.common.Configuration;

public class Files {
		
	private final static String META_DATA = "%s/meta.dat";
	
	private final static String TABLE_LOG = "%s/%s.log";
	
	private final static String INDEX = "%s/%s.idx";
	
	private final static String DISK_SEGMENT = "%s/%s_%s.seg";
	
	private final static String MEMORY_SEGMENT = "%s.mem";
	
	private final static String TABLE_DIRECTORY = "%s";
	
	private final static String SECURITY = "security.sec";
	
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
	
	
	public static File getTableLog(String table) {
		return getLogFile(TABLE_LOG, table, System.currentTimeMillis());
	}
	

	public static File getIndex(String table, String index) {
		return getFile(INDEX, table, index);
	}
	public static File getDiskSegment(String table, int count) {
		return getFile(DISK_SEGMENT, table, System.currentTimeMillis(), count);
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
