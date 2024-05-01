package org.art.db.common;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Configuration {
	
	@Override
	public int hashCode() {
		return Objects.hash(cluster, host, port, properties, shard);
	}


	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		Configuration other = (Configuration) obj;
		return Objects.equals(cluster, other.cluster) && Objects.equals(host, other.host)
				&& Objects.equals(port, other.port) && Objects.equals(properties, other.properties)
				&& Objects.equals(shard, other.shard);
	}

	private static final Logger logger = LoggerFactory.getLogger(Configuration.class);
	
	private String host; 
	private String port = "8080";
	private String cluster = "cluster"; 
	private String shard = "shard";
	private final Map<String, String> properties;
	private final PropertyChangeSupport pcs = new PropertyChangeSupport(this);
	
	private final static Map<String, String> defaults = 
			Map.of("org.art.db.logDelay",       "1s",
				   "org.art.db.documentCache",  "1M",
				   "org.art.db.maxSegmentSize", "100000",
				   "org.art.db.nodeCache",      "10M");
	
	
	private static Configuration instance;
	
	static {
		try {
			instance = new Configuration(InetAddress.getLocalHost().getHostAddress(), "8080", "s", "c").withProperty("org.art.db.maxBatch", "1");			
		} catch (Exception exc) {
			logger.info("Error getting local host " + exc.getMessage());
		}
	}
	

	Configuration(String host, String port, String cluster, String shard, Map<String, String> properties) {
		this.host = host;
		this.port = port;
		this.cluster = cluster;
		this.shard = shard;
		this.properties = properties;
		
		if (this.host.contains("localhost")) {
			try {
				this.host = InetAddress.getLocalHost().getHostAddress();
			} catch (Exception exc) {
				throw new DBRuntimeException(exc);
			}
		}
		
		if (instance != null) {
			for (PropertyChangeListener listener : instance.pcs.getPropertyChangeListeners()) {
				this.pcs.addPropertyChangeListener(listener);
			}
		}
		
	}
	
	
	public Configuration(String host, String port, String shard, String cluster) {
		this(host, port, cluster, shard, new HashMap<>());
	}
	
	public Configuration(String host, String port, String shard, String cluster, String dbRoot, String user, String password) {
		this(host, port, cluster, shard, new HashMap<>());
		
		this.properties.put("org.art.db.dbroot", "./dbroot");
		this.properties.put("org.art.db.user", user);
		this.properties.put("org.art.db.password", password);
		
		logger.info("build config for shard " + shard + " set this" + this.shard);
	}
	
	public void addPropertyChangeListener(PropertyChangeListener listener) {
		this.pcs.addPropertyChangeListener(listener);
		
		for (Map.Entry<String, String> entry : this.properties.entrySet()) {
			String name = entry.getKey();
			String value = entry.getValue();
			
			listener.propertyChange(new PropertyChangeEvent(this, name, null, value));
		}
		
    }
	
	public Map<String, String> getDynamicProperties() {
		Map<String, String> result = new HashMap<>();
		
		for (Map.Entry<String, String> entry : defaults.entrySet()) {
			String name = entry.getKey();
			String value = this.properties.containsKey(name) ? this.properties.get(name) : defaults.get(name);
			
			result.put(name, value);
		}
		
		return result;
	}
	
	public static Configuration getInstance() {
		return instance;
	}
	
	public String getDefault(String key) {
		return defaults.get(key);
	}
	
	public String getProperty(String key) {
		return properties.get(key);
	}
	
	public String getProperty(String key, String dflt) {
		String value = properties.get(key);
		
		return value == null ? dflt : value;
	}
	
	public void load(String propertyPath) throws Exception {
		try (InputStream stream = Configuration.class.getClassLoader().getResourceAsStream(propertyPath)) {
			Properties properties = new Properties();
			
			properties.load(stream);
			
			this.host = host.contains("localhost") ? InetAddress.getLocalHost().getHostAddress() : host;
			this.port = properties.getProperty("org.art.db.port", "8080");
			this.cluster = properties.getProperty("org.art.db.cluster", "cluster");
			this.shard = properties.getProperty("org.art.db.shard",  "shard");
			
			if (this.host.contains("localhost")) {
				this.host = InetAddress.getLocalHost().getHostAddress();
			}
			
			this.properties.clear();
			
			properties.forEach((x, y) -> {
				if (!x.equals("host")) {
					this.setProperty((String)x, (String)y);
				}
			});
					
			if (this.host.equalsIgnoreCase("localhost")) {
				host = InetAddress.getLocalHost().getHostAddress();
			}
		} catch (Exception exc) {
			throw new Exception("There was an error reading " + propertyPath);
		}
	}
	
	public void removeProperty(String name) {
		String oldValue = this.properties.remove(name);
		
		this.pcs.firePropertyChange(name, oldValue, null);
	}
	
	public synchronized void removePropertyChangeListener(PropertyChangeListener listener) {
		this.pcs.removePropertyChangeListener(listener);
    }
	
	public void setProperty(String name, String value) {
		String oldValue = this.properties.put(name, value);
		
		this.pcs.firePropertyChange(name, oldValue, value);
	}
	
	public Configuration withProperty(String name, String value) {
		this.properties.put(name, value);
		return this;
	}
	
	public URI toURI() {
		try {
			return new URI(String.format("http://%s:%s", this.host, this.port));
		} catch (Exception exc) {
			throw new IllegalStateException(exc);
		}
	}

	public String getCluster() {
		return cluster;
	}

	public String getShard() {
		return shard;
	}

	public String getHost() {
		return host;
	}

	public String getPort() {
		return port;
	}
}
