package org.art.storage;

public record ColumnInfo(Class<?> type, boolean primary, boolean nullable, Object defaultValue) {
	
}
