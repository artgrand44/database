package org.art.db.storage;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import org.art.db.common.Scheduler;
import org.art.db.common.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.vafer.jdeb.shaded.commons.io.FileUtils;

public class Environment {
	
	private static final Logger logger = LoggerFactory.getLogger(Environment.class);


	private final static Map<String, String> defaults = new HashMap<String, String>();
	
	
	static {
		defaults.putAll(Map.of(
				   "org.art.db.logDelay",       "1s",
				   "org.art.db.documentCache",  "1M",
				   "org.art.db.maxSegmentSize", "10000",
				   "org.art.db.nodeCache",      "10M",
				   "org.art.db.indexCache",     "10M",
				   "org.art.db.backuproot",     "./backup",
				   "org.art.db.backupinterval", "1w",
				   "org.art.db.backupmax",      "2",
				   "org.art.db.queueRecovery",  "3",
				   "org.art.db.console",        "false"));
		
		defaults.putAll(Map.of(
				   "org.art.db.refresh",        "5M",
				   "org.art.db.metricslogger",  "prometheus",
				   "org.art.db.dbtemp",         "./dbtemp",
				   "org.art.db.lang",           "en"));
	}

	private final static File KUBERNETES_CONFIG_FILE = new File("/config/db.properties");
	
	private final static Environment instance = new Environment();
	
	private File source;
	
	private long lastModified;
	
	private final Map<String, String> properties = new HashMap<>();
	
	private final PropertyChangeSupport pcs = new PropertyChangeSupport(this);
	
	
	private Environment() {
		for (String prop : defaults.keySet()) {
			String value = System.getenv(prop);
			
			if (value != null && !value.isBlank()) {
				this.properties.put(prop, value);
			}
		}
		
		if (Utils.isKubernetes()) {
			Scheduler.DEFAULT.scheduleAtFixedRate(() -> this.checkForRefresh(), 0, 10, TimeUnit.MINUTES);
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
	
	private synchronized void checkForRefresh() {
		Properties props = new Properties();
		File newFile;
	
		try {
			if (Utils.isKubernetes() && KUBERNETES_CONFIG_FILE.exists()) {
				try (InputStream stream = new FileInputStream(KUBERNETES_CONFIG_FILE)) {
					props.load(stream);
				} 
			} else if (this.source.exists() && (newFile = new File(this.source.getAbsolutePath())).lastModified() > this.lastModified) {
				this.source = newFile;
				this.lastModified = newFile.lastModified();
								
				try (InputStream stream = new FileInputStream(this.source)) {
					props.load(stream);					
				}
			} 
			
			boolean changed = false;

			for (String prop : defaults.keySet()) {
				String value = props.getProperty(prop);

				if (value != null) {
					if (!Utils.isKubernetes() && !Objects.equals(value, this.properties.get(prop))) {
						Broadcaster.getInstance().broadcast("/db/setproperty", (prop + "=" + value).getBytes());						
					}
					
					this.setProperty(prop, value);
					changed = true;
				}
				
				if (changed && !Utils.isKubernetes()) {
					this.save();
				}
			} 
		} catch (Exception exc) {
			logger.error("There as an exception refreshing the environment " + exc.getMessage());
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
		this.source = new File(propertyPath);
		this.lastModified = this.source.lastModified();
		
		try (InputStream stream = this.source.isAbsolute() ? new FileInputStream(this.source) : Environment.class.getClassLoader().getResourceAsStream(propertyPath)) {
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
		
		File temp = new File(getProperty("org.art.db.dbtemp", "./dbtemp"));
		
		temp.mkdirs();
		FileUtils.cleanDirectory(temp);
		
		Utils.setTempDir(temp);
		
		Interval interval = new Interval(this.getPropertyWithDefault("org.art.db.refresh"));
		
		Scheduler.DEFAULT.scheduleAtFixedRate(() -> this.checkForRefresh(), 0, interval.toMillis(), TimeUnit.MILLISECONDS);

	}
	
	public void removeProperty(String name) {
		String oldValue = this.properties.remove(name);
		
		this.pcs.firePropertyChange(name, oldValue, null);
	}
	
	public synchronized void removePropertyChangeListener(PropertyChangeListener listener) {
		this.pcs.removePropertyChangeListener(listener);
    }

	public synchronized void setProperty(String name, String value) {
		String oldValue = this.properties.put(name, value);
		
		if (!Objects.equals(value, oldValue)) {
			this.pcs.firePropertyChange(name, oldValue, value);
		}
	}
	
	public static Environment getInstance() {
		return instance;
	}

	@Override
	public String toString() {
		return "Environment [source=" + source + ", properties=" + properties + "]";
	}

	public String getSource() {
		return this.source.getPath();
	}
	
	public void save() throws Exception {
		try (FileOutputStream stream = new FileOutputStream(this.source)) {
			Properties props = new Properties();

			props.putAll(this.properties);
			props.store(stream, "");
		} catch (Exception exc) {
			logger.error("There was an error saving the property file " + exc.getMessage());
		}

		this.lastModified = this.source.lastModified();

	}
}
