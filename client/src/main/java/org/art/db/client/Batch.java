package org.art.db.client;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.art.db.common.Serializable;
import org.art.db.common.TypedInputStream;
import org.art.db.common.TypedOutputStream;

public class Batch implements Serializable<Batch> {
	private String database;
	private String shard;
	private List<ClientArgs> clientArgs = new ArrayList<>();
	
	Batch(String database, String shard) {
		this.database = database;
		this.shard = shard;
	}
	public void add(String statement, Object... args) {
		List<Object> list = new ArrayList<>(Arrays.asList(args));
		
		this.clientArgs.add(new ClientArgs(this.database, this.shard, statement, list));
	}
	
	@Override
	public void serialize(TypedOutputStream stream) throws IOException {
		stream.writeInt(this.clientArgs.size());
		
		for (ClientArgs arg : this.clientArgs) {
			arg.serialize(stream);
		}
		
	}
	
	@Override
	public Batch deserialize(TypedInputStream stream) throws IOException {
		int count = stream.readInt();
		
		for (int idx = 0; idx < count; idx++) {
			this.clientArgs.add(new ClientArgs().deserialize(stream));
		}
		
		return this;
	}

	
	public String getDatabase() {
		return database;
	}
	public void setDatabase(String database) {
		this.database = database;
	}
	public String getShard() {
		return shard;
	}
	public void setShard(String shard) {
		this.shard = shard;
	}
	public List<ClientArgs> getClientArgs() {
		return clientArgs;
	}
	public void setClientArgs(List<ClientArgs> clientArgs) {
		this.clientArgs = clientArgs;
	}
	
	
}
