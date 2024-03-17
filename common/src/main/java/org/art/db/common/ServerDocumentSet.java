package org.art.db.common;

import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

import org.apache.commons.io.input.CountingInputStream;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;


public class ServerDocumentSet extends Serializable<ServerDocumentSet> implements DocumentSet {
	
	private static final Logger logger = LoggerFactory.getLogger(ServerDocumentSet.class);
	
	private List<Document> list = new ArrayList<>();
	private TypedOutputStream outputStream;
	private File file;
	private int size;
	
	private static final int MAX_MEMORY = 1000;
	
	private static final Cache<File, TypedInputStream> cache = Caffeine.newBuilder().maximumSize(10000).evictionListener(ServerDocumentSet::onRemoval).build();
		
	public void add(Document doc)  {
		try {
			if (this.outputStream == null) {
				this.list.add(doc);
				this.size++;

				if (this.size == MAX_MEMORY) {
					this.file = File.createTempFile("docsset", ".dat");
					this.outputStream = new TypedOutputStream(new FileOutputStream(file));

					for (Document adoc : this.list) {
						this.outputStream.writeObject(adoc);
					}

					this.list.clear();
				}
			} else {
				this.outputStream.writeObject(doc);
				this.size++;
			}
		} catch (IOException exc) {
			throw new DBRuntimeException(exc.getMessage(), exc);
		}
	}
	
	public void addAll(DocumentSet set) {
		for (Document doc : set) {
			this.list.add(doc);
		}
	}
	
	public static byte[] fetch(String fileName, int index, int size) throws IOException {
		Iterator<Document> iterator = new FileBasedIterator(new File(fileName), size);
		
		for (int idx = 0; idx < idx; idx++) {
			iterator.next();
		}
		
		int count = Math.min(size - index, MAX_MEMORY);
		
		try (TypedOutputStream stream = new TypedOutputStream()) {
			stream.writeInt(count);
			
			for (int idx = 0; idx < count; idx++) {
				stream.writeObject(iterator.next());
			}
			
			return stream.toByteArray();
		}
	}
	
	public void freeze() throws IOException {
		if (this.outputStream != null) {
			this.outputStream.close();
			this.outputStream = null;
		}
	}
	
	public boolean hasFile() {
		return this.file != null;
	}
	
	@Override
	public Iterator<Document> iterator() {
		if (this.file == null) {
			return  this.list.iterator();
		} else {
			return new FileBasedIterator(this.file, this.size);
		}
	}

	public boolean isEmpty() {
		return this.size == 0;
	}
	
	public int size() {
		return this.size;
	}
	
	public void sort(Comparator<Document> comparator) throws Exception {
		if (this.file == null) {
			Collections.sort(this.list, comparator);
		} else {
			File newFile = DocumentSetSorter.sort(this.file, comparator);
			
			this.file.delete();
			
			this.file = newFile;
		}
	}

	@Override
	public int hashCode() {
		return this.file == null ? Objects.hash(list) : Objects.hash(this.file);
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		ServerDocumentSet other = (ServerDocumentSet) obj;
		
		if (this.file != null) {
			return this.file.equals(other.file);
		}
		
		return Objects.equals(list, other.list);
	}

	@Override
	public void serialize(TypedOutputStream stream) throws IOException {
		Configuration config = Configuration.getInstance();
		
		if (this.hasFile() && config != null) {
			stream.writeBoolean(true);
			stream.writeInt(this.size);
			stream.writeUTF(this.file.getAbsolutePath());
			stream.writeUTF(config.toURI().toString());
			
			int count = 0;
			
			for (Document doc : this) {
				stream.writeObject(doc);
				count++;
				
				if (count == MAX_MEMORY) {
					break;
				}				
			}
		} else {
			stream.writeBoolean(false);
			stream.writeObject(this.list);
		}
		
	}
	
	@SuppressWarnings("unchecked")
	@Override
	public ServerDocumentSet deserialize(TypedInputStream stream) throws IOException {
		
		this.list = (List<Document>)stream.readObject();
		this.size = list.size();		
		
		return this;
	}

	private static void onRemoval(@Nullable File key, @Nullable TypedInputStream stream, RemovalCause cause) {
		try {
			stream.close();
		} catch (Exception exc) {
			throw new IllegalStateException(exc);
		}
		
	}

	@Override
	public void close() throws IOException {
		this.freeze();
		
		if (this.file != null) {
			cache.invalidate(this.file);
			this.file.delete();
		}
	}
	
	static class FileBasedIterator implements Iterator<Document> {
		private long position;
		private long count;
		private final File file;
		private final int size;
		
		FileBasedIterator(File file, int size) {
			this.file = file;
			this.size = size;
		}
		
		@Override
		public boolean hasNext() {
			return this.count < this.size - 1;
		}

		@Override
		public Document next() {
			try {
				TypedInputStream stream = cache.getIfPresent(this.file);
			
				if (stream == null) {
					stream = new TypedInputStream(new CountingInputStream(new FileInputStream(this.file)));
					stream.skip(this.position);
					cache.put(this.file, stream);
				}
				
				Document doc = (Document)stream.readObject();
				
				CountingInputStream counter = (CountingInputStream) stream.getStream();
				
				this.position = counter.getByteCount();
				this.count++;
				
				return doc;		
			} catch (EOFException exc) {
				logger.info("got eof " + this.count);
				throw new IllegalStateException(exc);
			} catch (Exception exc)  {
				throw new IllegalStateException(exc);
			}
		}
		
	}

	@Override
	public String toString() {
		return "ServerDocumentSet [size=" + size + ", list=" + list + "]";
	}
	
	
}
