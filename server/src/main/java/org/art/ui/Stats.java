package org.art.ui;

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
		  @Type(value = ClientStats.class, name = "client") 
		})
public abstract class Stats implements Comparable<Stats> {
	private String cacheName;
	private String chard; 
	private String server; 
	
	public Stats() {

	}
	
	public Stats(String cacheName, String chard, String server) {
		this.cacheName = cacheName;
		this.chard = chard;
		this.server = server;
	}

	public String getCacheName() {
		return cacheName;
	}

	public void setCacheName(String cacheName) {
		this.cacheName = cacheName;
	}
	
	public String getShard() {
		return chard;
	}

	public void setShard(String shard) {
		this.chard = shard;
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
		int result = this.chard.compareTo(that.chard);
		
		if (result != 0) {
			return result;
		}
		
		if (this.server != null) {
			return this.server.compareTo(that.server);
		}
		
		return 0;
	}
}
