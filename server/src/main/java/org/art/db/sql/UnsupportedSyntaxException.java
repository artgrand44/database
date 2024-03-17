package org.art.db.sql;

import org.art.db.storage.DBRuntimeException;

public class UnsupportedSyntaxException extends DBRuntimeException {

	private static final long serialVersionUID = 1L;
	
	public UnsupportedSyntaxException(String message) {
		super(message);
	}

}
