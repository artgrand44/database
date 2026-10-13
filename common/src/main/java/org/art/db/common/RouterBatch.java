package org.art.db.common;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties({"future", "startTime"}) 

public class RouterBatch implements Serializable<RouterBatch> {
	
	private String uri; 
	private byte[] body;
	private CompletableFuture<byte[]> future;
	private long startTime;
	
	public RouterBatch() {
		
	}
	
	public RouterBatch(String uri, byte[] body, CompletableFuture<byte[]> future, long startTime)  {
		this.uri = uri;
		this.body = body;
		this.future = future;
		this.startTime = startTime;
	}

	@Override
	public RouterBatch deserialize(TypedInputStream stream) throws IOException {
		this.uri = stream.readUTF();
		this.body = new byte[stream.readInt()];
		
		stream.read(this.body);
		
		return this;
	}
	
	@Override
	public void serialize(TypedOutputStream stream) throws IOException {
		stream.writeUTF(this.uri);
		stream.writeInt(this.body.length);
		stream.write(this.body);		
	}

	
	public String getUri() {
		return uri;
	}

	public void setUri(String uri) {
		this.uri = uri;
	}

	public byte[] getBody() {
		return body;
	}

	public void setBody(byte[] body) {
		this.body = body;
	}

	public CompletableFuture<byte[]> getFuture() {
		return future;
	}

	public void setFuture(CompletableFuture<byte[]> future) {
		this.future = future;
	}

	public long getStartTime() {
		return startTime;
	}

	public void setStartTime(long startTime) {
		this.startTime = startTime;
	}

	
}
