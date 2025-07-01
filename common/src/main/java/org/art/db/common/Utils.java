package org.art.db.common;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Utils {
	private static final Logger logger = LoggerFactory.getLogger(Utils.class);
	
	private static File tempDir = null;
	
	public static void closeSafely(Closeable closable) {
		if (closable != null) {
			try {
				closable.close();
			} catch (Exception exc) {
				logger.info("close got exception " + exc.getMessage());
			}
		}
	}
	
	public static File createTempFile(String name, String extension) throws IOException {
		File file = tempDir == null ? File.createTempFile(name, extension) : File.createTempFile("sort", ".dat", tempDir.getAbsoluteFile());
		
		file.deleteOnExit();
		
		return file;
		
	}
	
	public static void sleepSafely(long millis) {
		try {
			Thread.sleep(millis);
		} catch (Exception exc) {
		}
	}
	
	public static boolean isKubernetes() {
		return "kubernetes".equalsIgnoreCase(System.getenv("cloud")) || System.getenv("KUBERNETES_PORT") != null;
	}

	public static File getTempDir() {
		return tempDir;
	}

	public static void setTempDir(File tempDir) {
		Utils.tempDir = tempDir;
	}
}
