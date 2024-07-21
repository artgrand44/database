package org.art.db.common;

import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.util.Objects;
import java.util.Properties;

public class ConnectionInfo {
	private final String host;
	private final String port; 
	private final String database;
	private final String shard;
	
	public ConnectionInfo(String host, String port, String shard, String datase) {
		this.host = host;
		this.port = port;
		this.database = datase;
		this.shard = shard;	
	}

	public ConnectionInfo(String propertyPath) throws Exception {
		try (InputStream stream = ConnectionInfo.class.getClassLoader().getResourceAsStream(propertyPath)) {
			Properties properties = new Properties();
			
			properties.load(stream);
			
			String host = properties.getProperty("org.art.db.host", "localhost");
			
			this.host = host.contains("localhost") ? InetAddress.getLocalHost().getHostAddress() : host;
			this.port = properties.getProperty("org.art.db.port", "8080");
			this.database = properties.getProperty("org.art.db.database", "cluster");
			this.shard = properties.getProperty("org.art.db.shard",  "shard");
		}
	}
			
	
	public String getHost() {
		return host;
	}

	public String getPort() {
		return port;
	}

	public String getDatabase() {
		return database;
	}

	public String getShard() {
		return shard;
	}
	
	public URI toURI() {
		
		if (this.host == null || this.port == null) {
			return null;
		}
		
		try {
			return new URI(String.format("http://%s:%s", this.host, this.port));
		} catch (Exception exc) {
			throw new IllegalStateException(exc);
		}
	}

	@Override
	public int hashCode() {
		return Objects.hash(database, host, port, shard);
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		ConnectionInfo other = (ConnectionInfo) obj;
		return Objects.equals(database, other.database) && Objects.equals(host, other.host)
				&& Objects.equals(port, other.port) && Objects.equals(shard, other.shard);
	}
}
