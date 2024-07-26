package org.art.db.common;

import java.io.Closeable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Utils {
	private static final Logger logger = LoggerFactory.getLogger(Utils.class);
	
	public static void closeSafely(Closeable closable) {
		try {
			closable.close();
		} catch (Exception exc) {
			logger.info("close got exception " + exc.getMessage());
		}
	}
	
	public static void sleepSafely(long millis) {
		try {
			Thread.sleep(millis);
		} catch (Exception exc) {
		}
	}
}
