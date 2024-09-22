package org.art.storage;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.InetAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.io.FileUtils;
import org.art.db.client.Client;
import org.art.db.common.DiscoveryHandler;
import org.art.db.common.Utils;
import org.art.db.server.Server;
import org.art.db.storage.RestoreManager;
import org.art.db.tests.TestAdapter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.function.Executable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class BaseDelegationTest extends TestAdapter {
	
	private static final Logger logger = LoggerFactory.getLogger(BaseDelegationTest.class);
	
	private final Class<?>	delegate;
		
	private Process externalServer;
	
	private final String java = System.getProperty("java.home") + "/bin/java";
	
	private static final File[] DATA_DIRECTORIES = new File[] {
			new File("./dbroot"),
			new File("./cluster2root"),
			new File("./atest"),
			new File("./secondroot"),
			new File("./duptest"),
		};
		
	
	protected BaseDelegationTest(Class<?> delegate) {
		this.delegate = delegate;
		
		for (File dir : DATA_DIRECTORIES) {
			if (dir.exists()) {
				try {
					FileUtils.cleanDirectory(dir);
				} catch (Exception exc) {
					logger.info("There was an error clearing " + dir.getAbsolutePath() + " " + exc.getMessage());
				}
			}
		}
	}
	
	
		
	@TestFactory 
	public List<DynamicTest> getTests() {
		List<DynamicTest> result = new ArrayList<>();
		
		try {				
				Method beforeEach = null;
				Method afterEach = null;
								
				for (Method method : this.delegate.getMethods()) {
					if (method.getAnnotation(BeforeEach.class) != null) {
						beforeEach = method;
					} else if (method.getAnnotation(AfterEach.class) != null) {
						afterEach = method;
					}
				}

				for (Method method : this.delegate.getMethods()) {
					int mods = method.getModifiers();
					
					if (method.getAnnotation(Test.class) != null && Modifier.isPublic(mods) || Modifier.isAbstract(mods)) {
						final Method before = beforeEach;
						final Method after = afterEach;

						Executable executable = () -> { doRun(before, after, method); };
						String name =  this.delegate.getSimpleName() + "." + method.getName();

						result.add(DynamicTest.dynamicTest(name, executable));
					}
				}			
		} catch (Exception exc) {
			logger.error("Error getting dynamic tests " + exc.getMessage());
		}
		
		return result;

	}
	
	public void doRun(Method beforeEach, Method afterEach, Method test) throws Throwable {
		Constructor<?> cons = this.delegate.getConstructor();
		Object instance = cons.newInstance();
		Throwable theException = null;
		
		TestAdapter.setInstance(this);
		
		try {
			if (beforeEach != null) {
				beforeEach.invoke(instance);
			}
			
			try {
				test.invoke(instance);	
			} catch (Exception exc) {
				theException = exc;
			}
			
			if (afterEach != null) {
				afterEach.invoke(instance);
			}
		} catch (Exception exc) {
			theException = exc;
		} finally {
			this.stopPrimary();
			this.stopSecondary();
		}
				
		if (theException instanceof InvocationTargetException exc) {
			throw exc.getCause();
		} else if (theException != null) {
			throw theException;
		}
	}

	@Override
	public URI startPrimary(String profile) {
		DiscoveryHandler.clear();
		Server.start(profile);	
		
		while (!RestoreManager.getInstance().isRecovered()) {
			Utils.sleepSafely(1000);
		}
		
		return this.getPrimaryURI();
		
	}

	@Override
	public void stopPrimary() {
		Server.stop();
		
	}

	@Override
	public URI startSecondary(String profile) {
		try {
			String classpath = System.getProperty("java.class.path");
			ProcessBuilder pb = new ProcessBuilder(java, "-cp", classpath, Server.class.getName(), profile);

			pb.directory(new File("."));
			pb.inheritIO();

			this.externalServer = pb.start();
			
			return this.getSecondardURI();
		} catch (Exception exc) {
			throw new IllegalStateException(exc);
		}
	}

	@Override
	public void stopSecondary() {
		if (this.externalServer != null) {
			this.externalServer.destroy();
			this.externalServer = null;
		}
		
	}

	@Override
	public Client getClusterClient(String user, String password) throws Exception {
		return new Client(new URI(getURI("8080").toString() + "/cluster"), user, password);
	}
	
	@Override
	public Client getPrimaryClient(String user, String password) throws Exception {
		return new Client(new URI(getURI("8080").toString() + "/cluster"), user, password);
	}
	
	@Override
	public Client getSecondaryClient(String user, String password) throws Exception {
		return new Client(new URI(getURI("8081").toString() + "/cluster"), user, password);
	}
	
	@Override
	public URI getPrimaryURI() {
		return getURI("8080");
	}
	
	
	@Override
	public URI getSecondardURI() {
		return getURI("8081");
	}
	
	private URI getURI(String port) {
		try {
			String host = InetAddress.getLocalHost().getHostAddress();

			return new URI(String.format("http://%s:%s", host, port));
		} catch (Exception exc) {
			throw new IllegalStateException(exc);
		}
	}
	
	
}
