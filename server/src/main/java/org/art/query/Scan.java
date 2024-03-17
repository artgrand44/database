package org.art.query;

import org.art.db.common.Key;
import org.art.storage.Comparison;

public class Scan{
	private final String index; 
	private final Comparison comparison; 
	private final Key key;
	private final Key extraKey;
	
	public Scan(String index, Comparison comparison,  Key key) {
		this(index, comparison, key, null);
	}

	public Scan(String index, Comparison comparison,  Key key, Key extraKey) {
		this.index = index;
		this.comparison = comparison;
		this.key = key;
		this.extraKey = extraKey;
	}
	public String getIndex() {
		return index;
	}

	public Comparison getComparison() {
		return comparison;
	}

	public Key getKey() {
		return key;
	}

	public Key getExtraKey() {
		return extraKey;
	}
}
