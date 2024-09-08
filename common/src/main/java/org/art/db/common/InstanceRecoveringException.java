package org.art.db.common;

public class InstanceRecoveringException extends RuntimeException {

	private static final long serialVersionUID = 1L;
	
	public InstanceRecoveringException() {
		super("The instance is still recovering");
	}

}
