package org.art.db.common;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.ref.SoftReference;
import java.util.concurrent.atomic.AtomicInteger;


public class Blob extends Serializable<Blob> {

	private int size;
	private byte[] bytes;
	private long firstBlock;
	private boolean onDisk;
	private SoftReference<Document> document;
	
	public Blob() {
		
	}
	
	public Blob(byte[] bytes) {
		this.bytes = bytes;	
		this.size = bytes.length;
		this.onDisk = false;
	}
	
	public Blob(long firstBlock, int size) {
		this.firstBlock = firstBlock;
		this.size = size;
		this.onDisk = true;
	}
	
	
	@Override
	public void serialize(TypedOutputStream stream) throws IOException {
		stream.writeBoolean(this.onDisk);
		stream.writeInt(this.size);
		
		if (this.onDisk) {
			stream.writeLong(this.firstBlock);
		} else if (this.size != 0) {
			stream.write(this.bytes);
		}
		
	}

	@Override
	public Blob deserialize(TypedInputStream stream) throws IOException {
		this.onDisk = stream.readBoolean();
		this.size = stream.readInt();
		
		if (this.onDisk) {
			this.firstBlock = stream.readLong();		
		} else if (size != 0){
			this.bytes = new byte[this.size];
		
			stream.read(this.bytes);
		}
		
		return this;
	}
	
	
	public byte[] getBytes() {
		return bytes;
	}
	
	public Document getDocument() {
		return this.document == null ? null : this.document.get();
	}
	
	
	public Blob fromDisk(BlobStore store) throws Exception {
		return store.fromDisk(this.firstBlock, this.size);
	}
	
	public void releaseDiskSpace(BlobStore store) throws Exception {
		if (this.onDisk) {
			store.releaseBlob(this.firstBlock);
		}
	}
		
	public void setDocument(Document doc) {
		this.document = new SoftReference<>(doc);
	}
	
	public Blob toDisk(BlobStore store) throws Exception {
		return this.onDisk ? this : store.toDisk(this.bytes);	
	}
	
	public Blob toStream(OutputStream ostream, long firstBlock, int blockSize, AtomicInteger blockCount) throws Exception {
		long blockId = firstBlock;
		int offset = 0;
		int nblocks = 0;
		
		while (offset < this.size) {
			int remainder = this.size - offset;
			long nextBlockId = remainder > blockSize - 8 ? blockId++ : -1;
			int bytesToWrite = Math.min(blockSize - 8, this.size - offset);
			
			try (TypedOutputStream stream = new TypedOutputStream()) {
				stream.writeLong(nextBlockId);
				
				stream.write(this.bytes, offset, bytesToWrite);
				
				byte[] bytes = stream.toByteArray();
				
				ostream.write(bytes);
				ostream.write(new byte[blockSize - bytes.length]);				
			}
			
			blockId = nextBlockId;
			offset += bytesToWrite;	
			nblocks++;
		}
		
		blockCount.set(nblocks);
		
		return new Blob(firstBlock, this.size);
	}
	
	
	public boolean isOnDisk() {
		return onDisk;
	}
	
		
}
