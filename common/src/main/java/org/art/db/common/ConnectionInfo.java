package org.art.db.common;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.util.Objects;
import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ConnectionInfo {
	@SuppressWarnings("unused")
	private static final Logger logger = LoggerFactory.getLogger(ConnectionInfo.class);
			
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

	public static ConnectionInfo fromKubernetes() throws Exception {
		String database = getenv("org.art.db.database", "cluster");
		String shard = getenv("org.art.db.shard",  "shard");
		
		return new ConnectionInfo(InetAddress.getLocalHost().getHostAddress(), "8080", shard, database);
	}
	
	public static ConnectionInfo fromProperties(String propertyPath) throws Exception {
		File file = new File(propertyPath);
		
		try (InputStream stream = file.isAbsolute() ? new FileInputStream(file) : ConnectionInfo.class.getClassLoader().getResourceAsStream(propertyPath)) {
			Properties properties = new Properties();
			
			properties.load(stream);
			
			String host = properties.getProperty("org.art.db.host", "localhost");
			
			String port = properties.getProperty("org.art.db.port", "8080");
			String database = properties.getProperty("org.art.db.database", "cluster");
			String shard = properties.getProperty("org.art.db.shard",  "shard");
			
			if (host.contains("localhost")) {
				host = InetAddress.getLocalHost().getHostAddress(); 
			}
			
			return new ConnectionInfo(host, port, shard, database);
		}
				
	}
	
	private static String getenv(String name, String dflt) {
		String value = System.getenv(name);
		
		return value == null ? dflt : value;
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

	@Override
	public String toString() {
		return "ConnectionInfo [host=" + host + ", port=" + port + ", database=" + database + ", shard=" + shard + "]";
	}
	
	
}
