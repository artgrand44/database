package org.art.db.storage;

import java.util.Objects;

public class Interval {
	private final String string;
	private final long milliSeconds;
	
	public Interval(String string) {
		Objects.nonNull(string);
		
		String trimmed = string.trim();
		
		if (trimmed.length() < 2) {
			throw new IllegalArgumentException("Invalid interval");			
		}
		
		String prefix = trimmed.substring(0,  trimmed.length() - 1);
		char unit = trimmed.charAt(trimmed.length() - 1);
		
		long number = Long.parseLong(prefix);
		long millis = 0;

		switch (unit) {
		case 'm':
			millis = number;
			break;
		case 'M':
			millis = number * 60 * 1000;
			break;
		case 's':
			millis = number * 1000;
			break;
		case 'h':
			millis = number * 60 * 60 * 1000;
			break;
		case 'd':
			millis = number * 24 * 60 * 60 * 1000;
			break;
		case 'w':
			millis = number * 7 * 24 * 60 * 60 * 1000;
			break;
		default:
			throw new IllegalArgumentException("Invalid interval " + unit);	
		}

		this.string = trimmed;
		this.milliSeconds = millis;
		
	}
	
	public long toMillis() {
		return this.milliSeconds;
	}
	
	@Override
	public String toString() {
		return this.string;
	}

}
