package org.art.db.common;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class MultiValueMap<TKey, TValue> extends HashMap<TKey, List<TValue>> {

	private static final long serialVersionUID = 1L;
	
	public void add(TKey key, TValue value) {
		List<TValue> list = this.computeIfAbsent(key, x -> new ArrayList<>());
		
		list.add(value);
	}

}
