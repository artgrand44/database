package org.art.db.common;

import java.io.File;
import java.lang.reflect.Constructor;
import java.net.URL;
import java.net.URLClassLoader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PluginLoader {

	private static final Logger logger = LoggerFactory.getLogger(PluginLoader.class);
	
	private ClassLoader pluginLoader;

	public PluginLoader() {
		try {
			String testLoaderDir = System.getProperty("org.cache.art.testLoader");
			
			if (testLoaderDir != null && !testLoaderDir.isEmpty()) {
				this.pluginLoader = new URLClassLoader(new URL[] { new File(testLoaderDir).toURI().toURL() }, this.getClass().getClassLoader());
				return;
			} 
			
			URL url = this.getClass().getProtectionDomain().getCodeSource().getLocation();
			File file = new File(url.getFile());
			File dir = file.isDirectory() ? file : file.getParentFile();
			File[] jars = dir.listFiles((f, n) -> n.startsWith("plugin") && n.endsWith(".jar"));

			if (jars != null && jars.length != 0) {
				this.pluginLoader = new URLClassLoader(new URL[] { jars[0].toURI().toURL() }, this.getClass().getClassLoader());
				return;
			}
				
			this.pluginLoader = this.getClass().getClassLoader();
		}  catch (Exception exc) {
			logger.info("Error creating plugin loader " + exc.getMessage());
		}
	}
	
	@SuppressWarnings("unchecked")
	public <T> T getPlugin(Class<? extends T> iface) {
		try {
			String className = "org.art.db.common." + iface.getSimpleName() + "Impl";
			Class<T> clazz = (Class<T>) this.pluginLoader.loadClass(className);
			Constructor<T> cons = clazz.getConstructor();

			return cons.newInstance();
		} catch (Exception exc) {
			logger.info(exc.getMessage());
			return null;
		}		
	}
}
