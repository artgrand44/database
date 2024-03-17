package org.art.db.storage;

public record ColumnInfo(Class<?> type, boolean primary, boolean nullable, Object defaultValue) {
	
}
