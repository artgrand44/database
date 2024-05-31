package org.art.db.common;

import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class DocumentSetSorter {
	
	private static File createTempFile() throws IOException {
		File file = File.createTempFile("sort", ".dat");
		
		file.deleteOnExit();
		
		return file;
		
	}
	
	private static File mergeFiles(File left, File right, Comparator<Document> comparator) throws Exception {
		File result = createTempFile();
		Document leftDoc = null;
		Document rightDoc = null;
		boolean eofLeft = false;
		boolean eofRight = false;
		
		try (TypedOutputStream outStream = new TypedOutputStream(result);
				TypedInputStream leftStream = new TypedInputStream(left);
				TypedInputStream rightStream = new TypedInputStream(right)) {
			
			for (;;) {
				if (leftDoc == null) {
					leftDoc = readDocument(leftStream);
					eofLeft = leftDoc == null;
				}
				
				if (rightDoc == null) {
					rightDoc = readDocument(rightStream);
					eofRight = rightDoc == null;
				}
				
				if (eofLeft || eofRight) {
					break;
				}
				
				int comp = comparator.compare(leftDoc, rightDoc);
				
				if (comp < 0) {
					outStream.writeObject(leftDoc);
					leftDoc = null;
				} else if (comp == 0) {
					outStream.writeObject(leftDoc);
					leftDoc = null;
					rightDoc = null;
				} else {
					outStream.writeObject(rightDoc);
					rightDoc = null;
				}
			}
			
			if (!eofLeft) {
				while ((leftDoc = readDocument(leftStream)) != null) {
					outStream.writeObject(leftDoc);
				}
			}
			
			if (!eofRight) {
				while ((rightDoc = readDocument(rightStream)) != null) {
					outStream.writeObject(rightDoc);
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
				File[] files = splitFile(file);

				sort(files[0], comparator);
				sort(files[1], comparator);

				File result = mergeFiles(files[0], files[1], comparator);

				files[0].delete();
				files[1].delete();

				return result;
			}
		} catch (Exception exc) {
			throw new DBRuntimeException("There was an error sorting the documents", exc);
		}
	}

	private static File[] splitFile(File file) throws IOException {
		File temp1 = createTempFile();
		File temp2 = createTempFile();
		
		try (TypedInputStream inStream = new TypedInputStream(file);
				TypedOutputStream outStream1  = new TypedOutputStream(temp1);
				TypedOutputStream outStream2  = new TypedOutputStream(temp2)) {
			
			int count = 0;
			
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
		
		return new File[] { temp1, temp2 };
	}
	
}
