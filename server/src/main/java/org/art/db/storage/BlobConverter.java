package org.art.db.storage;

import org.art.db.common.Blob;
import org.art.db.common.DBNull;
import org.art.db.common.Document;
import org.art.db.common.TypedInputStream;
import org.art.db.common.TypedOutputStream;

public class BlobConverter {
	
	static Document toDocument(Metadata metadata, Blob blob) throws Exception {
		Document doc = new Document();
		
		try (TypedInputStream stream = new TypedInputStream(blob.getBytes())) {
			for (String column : metadata.columns().keySet()) {
				try {
					doc.put(column, stream.readObject());
				} catch (Exception exc) {
					throw exc;
				}
			}
		}
		
		return doc;
	}
	
	static Blob fromDocument(Metadata metadata, Document doc) throws Exception {
		
		try (TypedOutputStream stream = new TypedOutputStream()) {
			for (String column : metadata.columns().keySet()) {
				Object value = doc.get(column);

				stream.writeObject(value == null ? DBNull.NULL : value);
			}

			return new Blob(stream.toByteArray());
		}
	}
	
}
