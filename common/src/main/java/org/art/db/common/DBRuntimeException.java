package org.art.db.common;

public class DBRuntimeException extends RuntimeException  {

	private static final long serialVersionUID = 1L;
	
	public DBRuntimeException(String message) {
		super(message);
	}

	public DBRuntimeException(String message, Throwable thr) {
		super(message, thr);
	}

	public DBRuntimeException(Throwable thr) {
		super("There was an SQL error", thr);
	}
}
