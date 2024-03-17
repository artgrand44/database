package org.art.db.common;

import java.io.Closeable;

public interface DocumentSet extends Iterable<Document>, Closeable {
	public int size();
	public boolean isEmpty();
}
