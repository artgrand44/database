package org.art.db.client;

import java.io.IOException;
import java.util.List;

import org.art.db.common.TypedInputStream;
import org.art.db.common.TypedOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ClientArgs {
	private String database;
	private String shard;
	private String sql;
	private List<?> parameters;
	
	private static final Logger logger = LoggerFactory.getLogger(ClientArgs.class);
	
	
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

	public void serialize(TypedOutputStream stream) throws IOException {
		stream.writeString(this.database);
		stream.writeString(this.shard);
		stream.writeString(this.sql);
		stream.writeObject(this.parameters);
		
	}

	@SuppressWarnings("unchecked")
	public ClientArgs deserialize(TypedInputStream stream) throws IOException {
		this.database = stream.readString();
		this.shard =  stream.readString();
		this.sql =  stream.readString();
		this.parameters = (List<Comparable<?>>)stream.readObject();
		
		return this;
	}

}
