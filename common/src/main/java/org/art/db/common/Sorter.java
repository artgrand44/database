package org.art.db.common;

import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Sorter<T> {
	private final Class<T> clazz;
	
	public Sorter(Class<T> clazz) {
		this.clazz = clazz;
	}
	
	@SuppressWarnings("unused")
	private static final Logger logger = LoggerFactory.getLogger(Sorter.class);
	
	
	
	private File merge(List<File> files, Comparator<T> comparator) throws Exception {
		List<T> current = new ArrayList<>();
		File result = Utils.createTempFile("sort", ".dat");
		List<TypedInputStream> inputs = new ArrayList<>();

		try (TypedOutputStream outStream = new TypedOutputStream(result)) {
			for (File file : files) {
				TypedInputStream input = new TypedInputStream(file);
				
				inputs.add(input);
				current.add((T)readDocument(input));
			}
		
		
			for (;;) {
				int smallestIndex = -1;
				T smallestDoc = null;
				TypedInputStream smallestInput = null;

				for (int idx = 0; idx < current.size(); idx++) {
					T doc  = current.get(idx);

					if (doc == null) {
						continue;
					} else if (smallestDoc == null || comparator.compare(doc, smallestDoc) < 0) {
						smallestIndex = idx;
						smallestDoc = doc;
						smallestInput = inputs.get(idx);
					} 
				}

				if (smallestDoc == null) {
					break;
				} else {
					outStream.writeObject(smallestDoc);
				} 

				current.set(smallestIndex,(T)readDocument(smallestInput));
			}
		} finally {
			for (TypedInputStream input : inputs) {
				Utils.closeSafely(input);
			}
		}

		return result;
		
	}

	
	private T readDocument(TypedInputStream stream) throws Exception {
		try {
			return this.clazz.cast(stream.readObject());
		} catch (EOFException exc) {
			return null;
		}
	}

	public File sort(File file, Comparator<T> comparator) throws DBRuntimeException {
		try {
			if (file.length() < 1024 * 1024) {
				List<T> list = new ArrayList<>();

				try (TypedInputStream stream = new TypedInputStream(file)) {
					for (;;) {
						list.add(this.clazz.cast(stream.readObject())); 
					}
				} catch (EOFException exc) {

				}

				Collections.sort(list, comparator);

				File result = Utils.createTempFile("sort", ".dat");

				try (TypedOutputStream stream = new TypedOutputStream(result)) {
					for (T doc : list) {
						stream.writeObject(doc);
					}
				}

				return result;
			} else {
				List<File> files = splitFile(file);

				for (File subfile : files) {
					sort(subfile, comparator);
				}
				
				File result = merge(files, comparator);

				for (File subfile : files) {
					subfile.delete();
				}

				return result;
			}
		} catch (Exception exc) {
			throw new DBRuntimeException("There was an error sorting the documents", exc);
		}
	}

	private static List<File> splitFile(File file) throws IOException {
		File temp1 = Utils.createTempFile("sort", ".dat");
		File temp2 = Utils.createTempFile("sort", ".dat");
		int count = 0;
		
		try (TypedInputStream inStream = new TypedInputStream(file);
				TypedOutputStream outStream1  = new TypedOutputStream(temp1);
				TypedOutputStream outStream2  = new TypedOutputStream(temp2)) {
					
			for (;;) {
				Document doc = (Document)inStream.readObject();
				
				if (count % 2 == 0) {
					outStream1.writeObject(doc);
				} else {
					outStream2.writeObject(doc);
				}
				
				count++;
			}		
		} catch (EOFException exc) {
		
		}
		
		return List.of(temp1, temp2);
	}
	
}
