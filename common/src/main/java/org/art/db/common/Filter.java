package org.art.db.common;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class Filter {
	private final Comparison comparison;
	private final String field;
	private final Object value;
	
	public Filter(Comparison comparison, String field, Object value)  {
		this.comparison = comparison;
		this.field = field;
		this.value = value;
	}
	
	@Override
	public int hashCode() {
		return Objects.hash(comparison, field, value);
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		Filter other = (Filter) obj;
		return comparison == other.comparison && Objects.equals(field, other.field)
				&& Objects.equals(value, other.value);
	}

	public Comparison getComparison() {
		return comparison;
	}
	
	public String getField() {
		return field;
	}
	

	public Key getKey() {
		return this.value instanceof Comparable comp ? new Key(comp) : null;
	}
	
	public List<Key> getKeys() {
		
		List<Key> result = new ArrayList<>();
		
		if (this.value instanceof List list) {
			for (Object item : list) {
				result.add(new Key((Comparable<?>)item));
			}
		}
		
		return result;
	}
	
	public Object getValue() {
		return value;
	}
	
	@Override
	public String toString() {
		return this.field + " " + this.comparison.getSql() + this.value.toString();
	}
}
