package org.art.db.common;

public class DBNull implements Comparable<DBNull>{
	public DBNull() {
		
	}
		
	@Override
	public int compareTo(DBNull that) {
		return 0;
	}
}
