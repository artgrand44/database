package org.art.db.common;

import java.io.IOException;
import java.util.List;

public class ClientArgs implements Serializable<ClientArgs>{
	private String database;
	private String shard;
	private String sql;
	private List<?> parameters;
	
	public ClientArgs() {
		
	}

	public ClientArgs(String database, String shard, String sql, List<?> parameters) {
		this.database = database;
		this.shard = shard;
		this.sql = sql;
		this.parameters = parameters;
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

	public String getSql() {
		return sql;
	}

	public void setSql(String sql) {
		this.sql = sql;
	}

	public List<?> getParameters() {
		return parameters;
	}

	public void setParameters(List<?> parameters) {
		this.parameters = parameters;
	}

	@Override
	public void serialize(TypedOutputStream stream) throws IOException {
		stream.writeUTF(this.database);
		stream.writeUTF(this.shard);
		stream.writeUTF(this.sql);
		stream.writeObject(this.parameters);
		
	}

	@SuppressWarnings("unchecked")
	@Override
	public ClientArgs deserialize(TypedInputStream stream) throws IOException {
		this.database = stream.readUTF();
		this.shard =  stream.readUTF();
		this.sql =  stream.readUTF();
		this.parameters = (List<Comparable<?>>)stream.readObject();
		
		return this;
	}

}
