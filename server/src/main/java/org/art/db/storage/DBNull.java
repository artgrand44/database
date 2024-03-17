package org.art.db.storage;

public class DBNull implements Comparable<DBNull>{
	public static DBNull NULL = new DBNull();
	
	private DBNull() {
		
	}

	@Override
	public int compareTo(DBNull o) {
		return 0;
	}
}
