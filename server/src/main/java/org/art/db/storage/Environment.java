package org.art.db.storage;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Environment {
	
	private static final Logger logger = LoggerFactory.getLogger(Environment.class);


	private final static Map<String, String> defaults = 
			Map.of("org.art.db.logDelay",       "1s",
				   "org.art.db.documentCache",  "1M",
				   "org.art.db.maxSegmentSize", "100000",
				   "org.art.db.nodeCache",      "10M",
				   "org.art.db.indexCache",     "10M",
				   "org.art.db.backuproot",     "./backup",
				   "org.art.db.backupinterval", "1w",
				   "org.art.db.backupmax",      "2",
				   "org.art.db.queueRecovery",  "3");

	private static Environment instance;
	
	static {
		try {
			instance = new Environment();			
		} catch (Exception exc) {
			logger.info("Error getting local host " + exc.getMessage());
		}
	} 
	
	private final Map<String, String> properties = new HashMap<>();
	
	private final PropertyChangeSupport pcs = new PropertyChangeSupport(this);
	
	private Environment() {
		for (String prop : defaults.keySet()) {
			String value = System.getenv(prop);
			
			if (value != null && !value.isBlank()) {
				this.properties.put(prop, value);
			}
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

	public void load(String propertyPath) throws Exception {
		try (InputStream stream = Environment.class.getClassLoader().getResourceAsStream(propertyPath)) {
			Properties properties = new Properties();
			
			properties.load(stream);
			
			this.properties.clear();
			
			properties.forEach((x, y) -> {
				if (!x.equals("host")) {
					this.setProperty((String)x, (String)y);
				}
			});
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

	public static Environment getInstance() {
		return instance;
	}
}
