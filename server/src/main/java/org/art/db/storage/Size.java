package org.art.db.storage;

import org.springframework.util.StringUtils;

public class Size {
	private final String string;
	private final long size;
	
	public Size(String string) {
		if (!StringUtils.hasText(string)) {
			throw new IllegalArgumentException("Invalid size");
		}
		
		String trimmed = string.trim();
		long size;
		
		try {
			if (trimmed.endsWith("M")) {
				size = Long.parseLong(trimmed.substring(0, trimmed.length() -1)) * 1024 * 1024;
			} else if (trimmed.endsWith("G")) {
				size = Long.parseLong(trimmed.substring(0, trimmed.length() -1)) * 1024 * 1024 * 1024;
			} else if (trimmed.endsWith("T")) {
				size = Long.parseLong(trimmed.substring(0, trimmed.length() -1)) * 1024 * 1024 * 1024 * 1024;
			} else {
				size = Long.parseLong(trimmed);
			}
			
			this.string = string;
			this.size = size;
		} catch (Exception exc) {
			throw new IllegalArgumentException("Invalid size", exc);
		}
	}
	
	public long getSize() {
		return this.size;
	}
	
	@Override
	public String toString() {
		return this.string;
	}
	
}
