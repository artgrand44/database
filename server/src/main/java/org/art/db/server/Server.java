package org.art.db.server;

import java.io.File;
import java.io.FileInputStream;
import java.net.InetAddress;
import java.security.CodeSource;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock.ReadLock;
import java.util.concurrent.locks.ReentrantReadWriteLock.WriteLock;

import org.apache.commons.io.IOUtils;
import org.art.db.common.ConnectionInfo;
import org.art.db.common.DiscoveryHandler;
import org.art.db.common.RestException;
import org.art.db.common.ZipUtils;
import org.art.db.security.SecurityHandler;
import org.art.db.storage.Environment;
import org.art.db.storage.Metrics;
import org.art.db.storage.PrometheusPublisher;
import org.art.db.storage.StorageManager;
import org.art.db.ui.UIConsole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.undertow.Undertow;
import io.undertow.UndertowOptions;
import io.undertow.io.Receiver.FullBytesCallback;
import io.undertow.server.DefaultByteBufferPool;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.HeaderValues;
import io.undertow.util.Headers;
import io.undertow.util.HttpString;

public class Server implements HttpHandler, FullBytesCallback {
	
	
	private static final Logger logger = LoggerFactory.getLogger(Server.class);
		
	private Undertow server;
	
	private final int port;
	
	private final DiscoveryHandler handler;
	
	private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
	private final ReadLock readLock = lock.readLock();
	private final WriteLock writeLock = lock.writeLock();
	
	private static Server instance;
	
	public Server(String properties) throws Exception {
		ConnectionInfo info = new ConnectionInfo(properties);
		
		this.handler =  new DiscoveryHandler(info, true);
		this.port = Integer.valueOf(this.handler.getLocalPort());
				
		logger.info("start server on " + port + " process " + ProcessHandle.current().pid());
		
		try {
			SecurityHandler.getInstance().setEnabled(true);
			Metrics.getInstance().logStatus("Starting");
			UIConsole.getInstance().start(info);
			PrometheusPublisher.getInstance().start();
			
			StorageManager.getInstance().recover(handler);
			
			this.handler.start();
			
			InetAddress address = InetAddress.getLocalHost();
			
			this.server = Undertow.builder()
								.setByteBufferPool(new DefaultByteBufferPool(false, 1024, 1024 * 1000, 10))
								.addHttpListener(port, address.getHostAddress(), this)
								.setServerOption(UndertowOptions.ENABLE_HTTP2, true)
								.setServerOption(UndertowOptions.SHUTDOWN_TIMEOUT, 2000)
								.build();
			
			this.server.start();
			Metrics.getInstance().logStatus("Running");
			logger.info("Started WebServer on port " + port);
		} catch (Throwable exc) {
			exc.printStackTrace();
			logger.error(exc.getClass().getName() + exc.getMessage());
		}		
	}
	
	/**
	 * Stop the server and exit.  This method does minimal cleanup, since the cleanup for
	 * one of the third parties (Undertow??) does a System.exit(0).  This would prevent
	 * the desired status code from getting to the parent.
	 */
	public static void exit(int status) {
		
		if (instance != null) {
			instance.writeLock.lock();
			StorageManager.getInstance().closeAll();
			instance.writeLock.unlock();
		}
		
		System.exit(status);
		
	}
	
	@Override
	public void handleRequest(HttpServerExchange exchange) throws Exception {
		exchange.startBlocking();
		if (exchange.isInIoThread()) {
			exchange.dispatch(this);
		} else if (exchange.getRequestMethod().toString().equalsIgnoreCase("get")) {
			this.handle(exchange, null);
		} else {		 
			exchange.getRequestReceiver().receiveFullBytes(this);
		}
	}
	

	@Override
	public void handle(HttpServerExchange exchange, byte[] message) {
		
		Map<String, Deque<String>> exchangeParams = exchange.getQueryParameters();
		Map<String, String> params = new HashMap<>();
		
		for (Map.Entry<String, Deque<String>> entry : exchangeParams.entrySet()) {
			params.put(entry.getKey(), entry.getValue().getFirst());
		}
		
		HeaderValues encoding = exchange.getRequestHeaders().get("Content-Encoding");
		
		if (encoding != null && encoding.contains("gzip")) {
			message = ZipUtils.decompress(message);
		}
		
		this.readLock.lock();
		
		try {
			Object response = Dispatcher.handle(exchange, exchange.getRequestURI(), params, message);
			
			if (response instanceof byte[] bytes) {
				exchange.getOutputStream().write(bytes);
			} else if (response != null){
				File file = (File)response;
				
				try (FileInputStream inputStream = new FileInputStream(file)) {
					IOUtils.copyLarge(inputStream, exchange.getOutputStream());
				}
			}
		} catch (UnathorizedException exc) {
			exchange.getResponseHeaders().put(Headers.WWW_AUTHENTICATE, "Basic realm=Database");
			exchange.setStatusCode(RestException.UNAUTHORIZED);
		} catch (Exception exc) {
			exc.printStackTrace();
			exchange.setStatusCode(RestException.TEA_POT);
			
			String warning = String.format("Warning: 199 - %s", exc.getMessage());
				
			exchange.getResponseHeaders().add(new HttpString("warning"), warning);	
		} finally {
			this.readLock.unlock();
		}
		
	}
	
	public static void main(String[] args) {
		String properties = args.length == 0 ? "application.properties" : args[0];
		boolean monitor = false;
		
		if (args.length == 0) {
			properties = "application.properties";
			monitor = false;
		} else {
			properties = args[0];
		}
		
		if (monitor) {
			monitor(properties);
		} else {
			start(properties);
		}
    }
	
	/**
	 * This method starts the server in an subprocess and waits for the subprocess
	 * to complete.  If the subprocess leaves a <pid>.dat file in the home directory
	 * then monitor() restarts the subprocess.  See exit() for more details on the 
	 *  <pid>.dat file.
	 * @param properties
	 */
	private static void monitor(String properties) {
		for (;;) {
			String java = System.getProperty("java.home") + "/bin/java";
			CodeSource source = Server.class.getProtectionDomain().getCodeSource();
			File sourceFile = new File(source.getLocation().getFile());
			ProcessBuilder pb;
			
			if (sourceFile.isDirectory()) {
				String classpath = System.getProperty("java.class.path");
				pb = new ProcessBuilder(java, "-cp", classpath, Server.class.getName(), properties);
			} else {
				pb = new ProcessBuilder(java, "-jar", sourceFile.getAbsolutePath(), Server.class.getName(), properties);
			}
			
			pb.directory(new File("."));
			pb.inheritIO();
			
			try {
				Process process = pb.start();

				if (process.waitFor() == 0) {
					logger.info("Monitor existing...");
					System.exit(0);
				}
			} catch (Exception exc) {
				logger.error("There was an exception starting the child " + exc.getMessage());
			}			
		}
	}
		
	public static void start(String properties) {
		try {
			logger.info("start with " + System.getProperty("java.home"));
			
			File file = new File(properties);
			
			logger.info("starting server with " + file.getAbsolutePath());
			Environment.getInstance().load(properties);
			instance = new Server(properties);
		} catch (Exception exc) {
			throw new IllegalStateException(exc);
		}		
	}
	
	public static void stop() {		
		if (instance != null) {
			Metrics.getInstance().logStatus("Stopping");
			instance.server.stop();
			instance.handler.stop();
			DiscoveryHandler.clear();
			StorageManager.getInstance().closeAll();
			UIConsole.getInstance().stop();	
		}
	}
	
	static class UnathorizedException extends RestException {

		private static final long serialVersionUID = 1L;

		public UnathorizedException(int status, String warning) {
			super(status, warning);
		}
		
	}

	
	
}
