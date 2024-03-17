package org.art.db.common;

public class DBNull implements Comparable<DBNull>{
	public static DBNull NULL = new DBNull();
	
	private DBNull() {
		
	}

	@Override
	public int compareTo(DBNull o) {
		return 0;
	}
}
