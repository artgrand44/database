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
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Configuration {
	
	private static final Logger logger = LoggerFactory.getLogger(Configuration.class);


	private final static Map<String, String> defaults = 
			Map.of("org.art.db.logDelay",       "1s",
				   "org.art.db.documentCache",  "1M",
				   "org.art.db.maxSegmentSize", "100000",
				   "org.art.db.nodeCache",      "10M",
				   "org.art.db.indexCache",     "10M",
				   "org.art.db.queueRecovery",  "3");

	private static Configuration instance;
	
	static {
		try {
			instance = new Configuration(InetAddress.getLocalHost().getHostAddress(), "8080", "s", "c").withProperty("org.art.db.maxBatch", "1");			
		} catch (Exception exc) {
			logger.info("Error getting local host " + exc.getMessage());
		}
	} 
	private String host;
	private String port = "8080"; 
	private String cluster = "cluster";
	private String shard = "shard";
	
	private final Map<String, String> properties;
	
	private final PropertyChangeSupport pcs = new PropertyChangeSupport(this);
	
	
	public Configuration(String cluster) {
		this(null, null, cluster, "*", new HashMap<>());
	}
	
	
	public Configuration(String host, String port, String shard, String cluster) {
		this(host, port, cluster, shard, new HashMap<>());
	}
	

	private Configuration(String host, String port, String cluster, String shard, Map<String, String> properties) {
		this.host = host;
		this.port = port;
		this.cluster = cluster;
		this.shard = shard;
		this.properties = properties;
		
		if (this.host != null && this.host.contains("localhost")) {
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
		
		if (KubernetesPlugin.isKubernetes()) {
			Scheduler.DEFAULT.scheduleAtFixedRate(this::loadFromKubernetes, 1, 30, TimeUnit.SECONDS);
		}
		
	}
	
	public void addPropertyChangeListener(PropertyChangeListener listener) {
		this.pcs.addPropertyChangeListener(listener);
		
		for (Map.Entry<String, String> entry : this.properties.entrySet()) {
			String name = entry.getKey();
			String value = entry.getValue();
			
			listener.propertyChange(new PropertyChangeEvent(this, name, null, value));
		}
		
    }
	
	
	public String getCluster() {
		return cluster;
	}
	
	public String getDefault(String key) {
		return defaults.get(key);
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
	
	public String getHost() {
		return host;
	}
	
	public String getPort() {
		return port;
	}
	
	public String getProperty(String key) {
		return properties.get(key);
	}
	
	public String getProperty(String key, String dflt) {
		String value = properties.get(key);
		
		return value == null ? dflt : value;
	}
	
	public String getPropertyWithDefault(String key) {
		return this.getProperty(key, this.getDefault(key));
	}
	
	public String getShard() {
		return shard;
	}
	
	
	
	@Override
	public int hashCode() {
		return Objects.hash(cluster, host, port, properties,  shard);
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
				&& Objects.equals(properties, other.properties)
				&& Objects.equals(shard, other.shard);
	}

	private void loadFromKubernetes() {
		this.properties.putAll(KubernetesPlugin.getInstance().getConfigurationProperies());
	}
	
	public void load(String propertyPath) throws Exception {
		try (InputStream stream = Configuration.class.getClassLoader().getResourceAsStream(propertyPath)) {
			Properties properties = new Properties();
			
			properties.load(stream);
			
			String host = properties.getProperty("org.art.db.host", "localhost");
			
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

	public Configuration withProperty(String name, String value) {
		this.properties.put(name, value);
		return this;
	}

	public static Configuration getInstance() {
		return instance;
	}
}
