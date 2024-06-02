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

public class DocumentSetSorter {
	
	@SuppressWarnings("unused")
	private static final Logger logger = LoggerFactory.getLogger(DocumentSetSorter.class);
	
	private static File createTempFile() throws IOException {
		File file = File.createTempFile("sort", ".dat");
		
		file.deleteOnExit();
		
		return file;
		
	}
	
	public static File merge(List<File> files, Comparator<Document> comparator) throws Exception {
		List<Document> current = new ArrayList<>();
		File result = createTempFile();
		List<TypedInputStream> inputs = new ArrayList<>();

		try (TypedOutputStream outStream = new TypedOutputStream(result)) {
			for (File file : files) {
				TypedInputStream input = new TypedInputStream(file);
				
				inputs.add(input);
				current.add(readDocument(input));
			}
		
		
			for (;;) {
				int smallestIndex = -1;
				Document smallestDoc = null;
				TypedInputStream smallestInput = null;

				for (int idx = 0; idx < current.size(); idx++) {
					Document doc  = current.get(idx);

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

				current.set(smallestIndex,readDocument(smallestInput));
			}
		} finally {
			for (TypedInputStream input : inputs) {
				try {
					input.close();
				} catch (Exception exc) {
					throw exc;
				}
			}
		}

		return result;
		
	}

	
	private static Document readDocument(TypedInputStream stream) throws Exception {
		try {
			return (Document)stream.readObject();
		} catch (EOFException exc) {
			return null;
		}
	}

	static File sort(File file, Comparator<Document> comparator) throws DBRuntimeException {
		try {
			if (file.length() < 1024 * 1024) {
				List<Document> list = new ArrayList<>();

				try (TypedInputStream stream = new TypedInputStream(file)) {
					for (;;) {
						list.add((Document)stream.readObject()); 
					}
				} catch (EOFException exc) {

				}

				Collections.sort(list, comparator);

				File result = createTempFile();

				try (TypedOutputStream stream = new TypedOutputStream(result)) {
					for (Document doc : list) {
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
		File temp1 = createTempFile();
		File temp2 = createTempFile();
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
