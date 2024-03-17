package org.art.db.common;



public class RestException extends Exception {
	private static final long serialVersionUID = 1L;
	
	private final int status;
	
	public static int WRONG_SHARD = 409;
	
	public static int UNAUTHORIZED = 401;
	
	public static int KEY_NOT_FOUND = 417;
	
	public static int TIMEOUT = 408;
	
	public static int NO_CONNECTION = 404;
	
	public static int TEA_POT = 418;
	
	public static int SERVER_DOWN = 500;
	
	public RestException(int status, String warning) {
		super("Bad status from rest call " + status + " " + warning);
		
		this.status = status;
	}

	public RestException(int status, String warning, Throwable cause) {
		super("Bad status from rest call " + status + " " + warning, cause);
		
		this.status = status;
	}

	public int getStatus() {
		return status;
	}

}
