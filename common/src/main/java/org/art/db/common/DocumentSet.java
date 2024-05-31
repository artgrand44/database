package org.art.db.common;

import java.io.Closeable;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.io.input.CountingInputStream;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;


public class DocumentSet extends Serializable<DocumentSet> implements Iterable<Document>, Closeable {
	
	private static final Logger logger = LoggerFactory.getLogger(DocumentSet.class);
	private static AtomicInteger nextId = new AtomicInteger();
	private List<Document> list = new ArrayList<>();
	private TypedOutputStream outputStream;
	private File file;
	private int size;
	private final int id = nextId.incrementAndGet();
	private boolean isRemote = false;
	private URI serverURI;
	private static final RestClient restClient = new RestClient();
	
	private static final int MAX_MEMORY = 1000;
	
	private static final Cache<File, TypedInputStream> cache = Caffeine.newBuilder().maximumSize(10000).evictionListener(DocumentSet::onRemoval).build();
		
	public DocumentSet() {
		logger.info("create " + this.id);
	}
	public void add(Document doc)  {
		try {
			if (this.outputStream == null) {
				this.list.add(doc);
				this.size++;
				if (this.size >= 1999) {
					logger.info("id = " + id + " " + this.size);
				}
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
				if (this.size >= 1999) {
					logger.info("id = " + id + " " + this.size);
				}
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
		Iterator<Document> iterator = new FileBasedIterator(new File(fileName), size, -1);
		
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
		if (this.isRemote) {
			return new RemoteIterator(this.file, this.list, this.size, this.serverURI);
		} else if (this.file == null) {
			return  this.list.iterator();
		} else {
			return new FileBasedIterator(this.file, this.size, this.id);
		}
	}

	public boolean isEmpty() {
		return this.size == 0;
	}
	
	public int size() {
		return this.size;
	}
	
	public DocumentSet merge(List<DocumentSet> others, Comparator<Document> comparator, long offset, long limit) {
		Iterator<Document> iterator1 = this.iterator();
		int count = 0;
		
		/*while (iterator1.hasNext()) {
			iterator1.next();
			count++;
		}
		
		if (count != this.size) {
			logger.info("Bad count at start");
		}*/
		DocumentSet result = new DocumentSet();
		List<Document> current = new ArrayList<>();
		List<Iterator<Document>> iterators = new ArrayList<>();
		long skipped = 0;

		iterators.add(this.iterator());

		for (DocumentSet set : others) {
			iterators.add(set.iterator());
		}

		logger.info("iterators " + iterators);
		for (Iterator<Document> iterator : iterators) {
			current.add(iterator.hasNext() ? iterator.next() : null);
		}
		
		while (result.size < limit) {
			int smallestIndex = -1;
			Document smallestDoc = null;
			Iterator<Document> smallestIterator = null;

			for (int idx = 0; idx < current.size(); idx++) {
				Document doc  = current.get(idx);
				
				if (doc == null) {
					continue;
				} else if (smallestDoc == null || comparator.compare(doc, smallestDoc) < 0) {
					smallestIndex = idx;
					smallestDoc = doc;
					smallestIterator = iterators.get(idx);
				} 
			}

			if (smallestDoc == null) {
				break;
			} else if (skipped >= offset) {
				result.add(smallestDoc);
			} else {
				skipped++;
			}

			current.set(smallestIndex, smallestIterator.hasNext() ? smallestIterator.next() : null);
		}

		iterator1 = this.iterator();
		count = 0;
		
		while (iterator1.hasNext()) {
			iterator1.next();
			count++;
		}
		
		if (count != this.size) {
			logger.info("Bad count at end");
		}
		
		if (others.isEmpty() && this.size != result.size) {
			logger.info("wrong size");
		}
		
		if (others.isEmpty()) {
			iterator1 = result.iterator();
			count = 0;
			
			while (iterator1.hasNext()) {
				iterator1.next();
				count++;
			}
			
			if (count != result.size) {
				logger.info("Mistach");
			}
		}
		return result;
		
	}
	
	
	public void sort(Comparator<Document> comparator) throws DBRuntimeException {
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
		DocumentSet other = (DocumentSet) obj;
		
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
	public DocumentSet deserialize(TypedInputStream stream) throws IOException {
		this.isRemote = stream.readBoolean();

		if (this.isRemote) {
			try {
				this.size = stream.readInt();
				if (this.size >= 1999) {
					logger.info("id = " + id + " " + this.size);
				}
				this.file = new File(stream.readUTF());
				this.serverURI = new URI(stream.readUTF());

				for (int idx = 0; idx < Math.min(this.size, MAX_MEMORY); idx++) {
					this.list.add((Document)stream.readObject());
				}			
			} catch (URISyntaxException exc) {
				throw new IOException("There was an error deserializing the DocumentSet", exc);
			}
		} else {
			this.list = (List<Document>)stream.readObject();
			this.size = this.list.size();
			if (this.size >= 1999) {
				logger.info("id = " + id + " " + this.size);
			}
		}

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
	

	@Override
	public String toString() {
		return "DocumentSet [size=" + size + ", list=" + list + "]";
	}
	
	static class FileBasedIterator implements Iterator<Document> {
		private long position;
		private long count;
		private final File file;
		private final int size;
		private final String id;
		private final TypedInputStream stream;
		private static AtomicInteger next = new AtomicInteger();
		
		FileBasedIterator(File file, int size, int id) {
			this.file = file;
			this.size = size;
			this.id = "" + id + "." + next.incrementAndGet();
			
			try {
				this.stream = new TypedInputStream(new CountingInputStream(new FileInputStream(this.file)));
			} catch (Exception exc) {
				exc.printStackTrace();
				throw new IllegalStateException(exc);
			}
			logger.info(this.id + " created len " + this.file.length());
		}
		
		@Override
		public boolean hasNext() {
			return this.count < this.size;
		}

		@Override
		public Document next() {
			try {
				/*TypedInputStream stream = cache.getIfPresent(this.file);
			
				if (stream == null) {
					logger.info(id + " getting stream " + file.getAbsolutePath() + " length " + this.file.length() + " position = " + this.position);
					stream = new TypedInputStream(new CountingInputStream(new FileInputStream(this.file)));
					stream.skip(this.position);
					cache.put(this.file, stream);
				}*/
				
				Document doc = (Document)stream.readObject();
				
				CountingInputStream counter = (CountingInputStream) stream.getStream();
				
				this.position = counter.getByteCount();
				this.count++;
				
				return doc;		
			} catch (EOFException exc) {
				logger.info("got eof  " + this);
				throw new IllegalStateException(exc);
			} catch (Exception exc)  {
				throw new IllegalStateException(exc);
			}
		}

		@Override
		public String toString() {
			return "FileBasedIterator [id=" + id + ", count=" + count + ", size=" + size + "]";
		}
		
		
	}

	static class RemoteIterator implements Iterator<Document> {

		private int index = 0;
		private int listOffset = 0;
		private final int size;
		private final List<Document> list;
		private final URI serverURI;
		private final File file;
		
		RemoteIterator(File file, List<Document> list, int size, URI serverURI) {
			this.list = list;
			this.size = size;
			this.serverURI = serverURI;
			this.file = file;
		}
		
		@Override
		public boolean hasNext() {
			return this.index < this.size;
		}

		@Override
		public Document next() {
		
			if (this.listOffset == this.list.size()) {
				try {
					String suffix = String.format("%s?file=%s&index=%s&size=%s", "/db/fetch", this.file.getAbsoluteFile(), this.index, this.size);
					byte[] bytes = restClient.get(restClient.buildURI(this.serverURI, suffix));
					
					this.list.clear();
					
					try (TypedInputStream stream = new TypedInputStream(bytes)) {
						int count = stream.readInt();
						
						for (int idx = 0; idx < count; idx++) {
							this.list.add((Document)stream.readObject());
						}					
					}
					
					this.listOffset = 0;
				} catch (Exception exc) {
					throw new IllegalStateException("There was an error fetching the documents", exc);
				}
			}
			
			Document doc = this.list.get(this.listOffset);
			
			this.listOffset++;
			this.index++;
			
			return doc;
		}
		
	}

	
}
