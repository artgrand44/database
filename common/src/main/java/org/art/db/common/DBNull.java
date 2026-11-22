package org.art.db.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DBNull implements Comparable<DBNull>{
	private static final Logger logger = LoggerFactory.getLogger(DBNull.class);
	
	private Object value = "";
	
	public DBNull() {
		
	}
	
	public DBNull(Object value) {
		this.value = value;
		
		if (value == null) {
			logger.info("null");
		}
	}
	

	@Override
	public int compareTo(DBNull that) {
		return 0;
	}

	public Object getValue() {
		if (value == null) {
			logger.info("null");
		}
		return value;
	}

	public void setValue(Object value) {
		if (value == null) {
			logger.info("null");
		}
		this.value = value;
	}
}
