package org.art.db.common;

import java.util.function.BiFunction;
import java.util.List;

public enum Comparison {
	NE((x, y)       -> compare(x, y) != 0,                true, "<>"),
	GE((x, y)       -> compare(x, y) >= 0,                true, ">="),
	GT((x, y)       -> compare(x, y) > 0,                 true, ">"),
	LE((x, y)       -> compare(x, y) <= 0,                true, "<="),
	LT((x, y)       -> compare(x, y) < 0,                 true, "<"),
	MATCH((x, y)    -> ((String)x).matches((String)y),    false, "match"),
	IN((x, y)       -> ((List<?>)y).contains(x),          true, "in"),
	EQ((x, y)       -> compare(x, y) == 0,                true, "="),
	NULL((x, y)     -> x == null,                         false, "null"),
	NOT_NULL((x, y) -> x != null,                         false, "not null"),
	BETWEEN((x, y)  -> between(x, y),                     true, "between");

	private final boolean indexable;
	private final String sql;
	private final BiFunction<Object, Object, Boolean> matcher;
		
	Comparison(BiFunction<Object, Object, Boolean> matcher, boolean indexable, String sql) {
		this.indexable = indexable;
		this.sql = sql;
		this.matcher = matcher;
	}
	
	public boolean isIndexable() {
		return this.indexable;
	}
	
	public <T extends Comparable<T>> boolean matches(Object actual, Object expected, Class<?> clazz) {
			
		try {
			return actual != null && this.matcher.apply(actual, expected);
		} catch (ClassCastException exc) {
			throw new IllegalArgumentException("Invalid datatype for " + this);
		}
		
	}
	
	private static  boolean between(Object actual, Object expected) {
		List<?> list = (List<?>)expected;
		
		return compare(actual, list.get(0)) >= 0 && compare(actual, list.get(1)) <= 1;
		
	}
	
	public static int compare(Object obj1, Object obj2) {
		if (obj1 == DBNull.NULL && obj2 != DBNull.NULL) {
			return -1;
		} else if (obj1 != DBNull.NULL && obj2 == DBNull.NULL) {
			return +1;
		} else {
			return compare(obj1, obj2, obj2.getClass());
		}
	}
	
	@SuppressWarnings("unchecked")
	private static <T> int compare(Object obj1, Object obj2, Class<T> clazz) {
		Comparable<T> typed1 = (Comparable<T>)obj1;
		T typed2 = clazz.cast(obj2);
		
		return typed1.compareTo(typed2);
	}

	public String getSql() {
		return sql;
	}
	
	
	
}
