package org.art.db.common;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class ClientDocumentSet extends Serializable<ClientDocumentSet> implements DocumentSet {
	
	@SuppressWarnings("unused")
	private static final Logger logger = LoggerFactory.getLogger(ClientDocumentSet.class);
	
	private List<Document> list = new ArrayList<>();
	private File file;
	private int size;
	private boolean isRemote = false;
	private URI serverURI;
	private static final RestClient restClient = new RestClient();
	
	private static final int MAX_MEMORY = 1000;
	
	
	@Override
	public Iterator<Document> iterator() {
		if (this.isRemote) {
			return new RemoteIterator(this.file, this.list, this.size, this.serverURI);
		} else {
			return  this.list.iterator();
		} 
	}

	public boolean isEmpty() {
		return this.size == 0;
	}
	
	public int size() {
		return this.size;
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
		ClientDocumentSet other = (ClientDocumentSet) obj;
		
		if (this.file != null) {
			return this.file.equals(other.file);
		}
		
		return Objects.equals(list, other.list);
	}

	@Override
	public void serialize(TypedOutputStream stream) throws IOException {
		stream.writeInt(this.size);
		stream.writeUTF(this.file.getAbsolutePath());
		stream.writeUTF(this.serverURI.toString());

		int count = 0;

		for (Document doc : this) {
			stream.writeObject(doc);
			count++;

			if (count == MAX_MEMORY) {
				break;
			}

		}
	}
	
	@SuppressWarnings("unchecked")
	@Override
	public ClientDocumentSet deserialize(TypedInputStream stream) throws IOException {
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

