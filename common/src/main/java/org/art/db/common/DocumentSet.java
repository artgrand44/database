package org.art.db.common;

import java.io.Closeable;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;


@JsonIgnoreProperties("empty")
public class DocumentSet implements Serializable<DocumentSet>, Iterable<Document>, Closeable {
	
	private static final Logger logger = LoggerFactory.getLogger(DocumentSet.class);
	
	private List<Document> list = new ArrayList<>();
	private TypedOutputStream outputStream;
	private File file;
	private int size;
	private boolean isRemote = false;
	private URI serverURI;
	private static final RestClient restClient = new RestClient();
	
	private static final int MAX_MEMORY = 1000;
	
	public void add(Document doc)  {
		try {
			if (this.outputStream == null) {
				this.list.add(doc);
				this.size++;
				
				if (this.size == MAX_MEMORY) {
					this.file = Utils.createTempFile("docsset", ".dat");
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
		
		this.size += set.size;
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
		if (this.isRemote) {
			return new RemoteIterator(this.file, this.list, this.size, this.serverURI);
		} else if (this.file == null) {
			return this.list.iterator();
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
	
		
	
	public void sort(File tempDir, Comparator<Document> comparator) throws DBRuntimeException {
		if (this.file == null) {
			Collections.sort(this.list, comparator);
		} else {
			Sorter<Document> sorter = new Sorter<>(Document.class);
			File newFile = sorter.sort(this.file, comparator);
			
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
		if (this.hasFile()) {
			stream.writeBoolean(true);
			stream.writeInt(this.size);
			stream.writeUTF(this.file.getPath());
			stream.writeUTF(DiscoveryHandler.getServerInstance().getLocalInstance().toString());
			
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
		}

		return this;

	}

	
	@Override
	public void close() throws IOException {
		this.freeze();
		
		if (this.file != null) {
			FileBasedIterator.cache.invalidate(this.file);
			this.file.delete();
		}
	}
	

	@Override
	public String toString() {
		return "DocumentSet [size=" + size + ", list=" + list + "]";
	}
	
	public int weight() {
		if (this.list.isEmpty()) {
			return 1;
		} else if (this.isRemote) {
			return this.list.getFirst().getSize() * MAX_MEMORY;
		} else {
			return this.list.getFirst().getSize() * this.size;
		} 
	}
	
	static class FileBasedIterator implements Iterator<Document> {
		private long position;
		private long count;
		private final File file;
		private final int size;
		
		private static final Duration TIMEOUT = Duration.ofMinutes(5);
		
		private static final Cache<File, TypedInputStream> cache = Caffeine.newBuilder()
				                                                  		   .maximumSize(10000)
				                                                  		   .expireAfterWrite(TIMEOUT)
				                                                  		   .removalListener(FileBasedIterator::onRemoval).build();
		
	
		FileBasedIterator(File file, int size) {
			this.file = file;
			this.size = size;
		}
		
		@Override
		public boolean hasNext() {
			if (this.count < this.size) {
				return true;
			}
			
			cache.invalidate(this.file);
			return false;
		}

		@Override
		public Document next() {
			try {
				TypedInputStream stream = cache.getIfPresent(this.file);
			
				if (stream == null && this.file.exists()) {
					stream = new TypedInputStream(new CountingInputStream(new FileInputStream(this.file)));
					stream.skip(this.position);
					cache.put(this.file, stream);
				} else if (stream == null) {
					throw new DBRuntimeException("The cursor has been times out");
				}
				
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
		
		private static void onRemoval(@Nullable File key, @Nullable TypedInputStream stream, RemovalCause cause) {
			Utils.closeSafely(stream);
			
			if (key.exists() && key.lastModified() + TIMEOUT.toMillis() < System.currentTimeMillis()) {
				key.delete();
			}
		}

		@Override
		public String toString() {
			return "FileBasedIterator [count=" + count + ", size=" + size + "]";
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
					String suffix = String.format("%s?file=%s&index=%s&size=%s", "/db/fetch", this.file.getPath(), this.index, this.size);
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

	public List<Document> getList() {
		return list;
	}

	public void setList(List<Document> list) {
		this.list = list;
	}

	public File getFile() {
		return file;
	}

	public void setFile(File file) {
		this.file = file;
	}

	public int getSize() {
		return size;
	}

	public void setSize(int size) {
		this.size = size;
	}

	public boolean isRemote() {
		return isRemote;
	}

	public void setRemote(boolean isRemote) {
		this.isRemote = isRemote;
	}

	public URI getServerURI() {
		return serverURI;
	}

	public void setServerURI(URI serverURI) {
		this.serverURI = serverURI;
	}

	
}
