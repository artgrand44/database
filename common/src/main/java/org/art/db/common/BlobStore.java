package org.art.db.common;

public interface BlobStore {
	Blob fromDisk(long firstBlock, int size) throws Exception;
	Blob toDisk(byte[] bytes) throws Exception;
	void releaseBlob(long firstBlock) throws Exception;
}
