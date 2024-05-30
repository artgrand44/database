package org.art.db.common;

import java.util.Comparator;
import java.util.List;

public class DocumentComparer implements Comparator<Document> {
	private final List<OrderBy> orderBy;
	
	public DocumentComparer(List<OrderBy> orderBy) {
		this.orderBy = orderBy;
	}
	
	public int compare(Document first, Document second) {
		
		for (OrderBy orderBy : this.orderBy) {
			String name = orderBy.field();

			Object firstField = first.get(name);
			Object secondField = second.get(name);

			if (firstField instanceof DBNull && ! (secondField instanceof DBNull)) {
				return -1;
			}
			
			if (secondField instanceof DBNull && ! (firstField instanceof DBNull)) {
				return +1;
			}
			
			int comp = Comparison.compare(firstField, secondField);

			if (comp != 0) {
				return orderBy.ascending() ? comp : - comp;
			}
		}
		
		return 0;
	}
}
