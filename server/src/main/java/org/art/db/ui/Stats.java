package org.art.db.ui;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonSubTypes.Type;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(
		  use = JsonTypeInfo.Id.NAME, 
		  include = JsonTypeInfo.As.PROPERTY, 
		  property = "type")
		@JsonSubTypes({ 
		  @Type(value = QueryStats.class, name = "query"), 
		  @Type(value = ServerStats.class, name = "server"),
		  @Type(value = ClientStats.class, name = "client"),
		  @Type(value = AlertStats.class, name = "alerts")
		})

public abstract class Stats implements Comparable<Stats> {
	private String cacheName;
	private String shard; 
	private String server; 
	
	public Stats() {

	}
	
	public Stats(String cacheName, String chard, String server) {
		this.cacheName = cacheName;
		this.shard = chard;
		this.server = server;
	}

	public abstract void computeAverages() ;
	
	public String getCacheName() {
		return cacheName;
	}

	public void setCacheName(String cacheName) {
		this.cacheName = cacheName;
	}
	
	public String getShard() {
		return shard;
	}

	public void setShard(String shard) {
		this.shard = shard;
	}

	public String getServer() {
		return server;
	}

	public void setServer(String server) {
		this.server = server;
	}

	public abstract <T extends Stats> Stats average(String cache, String shard, List<T> list);
	
	@Override
	public int compareTo(Stats that) {
		int result = this.shard.compareTo(that.shard);
		
		if (result != 0) {
			return result;
		}
		
		if (this.server != null) {
			return this.server.compareTo(that.server);
		}
		
		return 0;
	}
}
