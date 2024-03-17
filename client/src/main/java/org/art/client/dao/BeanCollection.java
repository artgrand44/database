package org.art.client.dao;

import java.util.Iterator;

import org.art.db.common.Document;
import org.art.db.common.DocumentSet;

public class BeanCollection<T> implements Iterable<T>{

	private final DocumentSet set;
	private final Class<T> clazz;
	
	BeanCollection(DocumentSet set, Class<T> clazz) {
		this.set = set;
		this.clazz = clazz;
	}
	

	@Override
	public Iterator<T> iterator() {
		return new BeanIterator(this.set, this.clazz);
	}
	
	class BeanIterator implements Iterator<T> {
		
		private final Class<T> clazz;
		private final Iterator<Document> iterator;
		
		BeanIterator(DocumentSet set, Class<T> clazz) {
			this.clazz = clazz;
			this.iterator = set.iterator();
		}
		@Override
		public boolean hasNext() {
			return this.iterator.hasNext();
		}

		@Override
		public T next() {
			try {
				Object bean = BeanMapper.toBean(this.iterator.next(), this.clazz);

				return this.clazz.cast(bean);
			} catch (Exception exc) {
				throw new IllegalStateException("There was an error converting the document", exc);
			}
		}

	}
}
