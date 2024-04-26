package org.art.db.server;

import java.io.File;
import java.io.FileInputStream;
import java.lang.ProcessBuilder.Redirect;
import java.net.InetAddress;
import java.security.CodeSource;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

import org.apache.commons.io.IOUtils;
import org.art.db.common.Configuration;
import org.art.db.common.DiscoveryHandler;
import org.art.db.common.RestException;
import org.art.db.common.ZipUtils;
import org.art.db.security.SecurityHandler;
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
	
	private static Server instance;
	
	public Server() throws Exception {
		Configuration config = Configuration.getInstance();
		
		this.port = Integer.valueOf(config.getPort());
		this.handler =  new DiscoveryHandler(config, true);
		
		try {
			SecurityHandler.getInstance().setEnabled(true);
			Metrics.getInstance().logStatus("Starting");
			UIConsole.getInstance().start();
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
			logger.error(exc.getClass().getName() + exc.getMessage());
		}		
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
		
		try {
			Object response = Dispatcher.handle(exchange, exchange.getRequestURI(), params, message);
			
			if (response instanceof byte[] bytes) {
				exchange.getOutputStream().write(bytes);
			} else {
				File file = (File)response;
				
				try (FileInputStream inputStream = new FileInputStream(file)) {
					IOUtils.copyLarge(inputStream, exchange.getOutputStream());
				}
			}
		} catch (UnathorizedException exc) {
			exchange.getResponseHeaders().put(Headers.WWW_AUTHENTICATE, "Basic realm=Database");
			exchange.setStatusCode(RestException.UNAUTHORIZED);
		} catch (Exception exc) {
			exchange.setStatusCode(RestException.TEA_POT);
			
			String warning = String.format("Warning: 199 - %s", exc.getMessage());
				
			exchange.getResponseHeaders().add(new HttpString("warning"), warning);	
		}
		
	}
	
	public static void main(String[] args) {
		String properties = args.length == 0 ? "application.properties" : args[0];
		boolean monitor;
		
		if (args.length == 0) {
			properties = "application.properties";
			monitor = false;
		} else {
			properties = args[0];
			monitor = args.length == 2 && args[1].equals("-monitor");
		}
		
		if (monitor) {
			monitor(properties);
		} else {
			start(properties);
		}
    }
	
	private static void monitor(String properties) {
		for (;;) {
			String java = System.getProperty("java.home") + "/bin/java";
			CodeSource source = Server.class.getProtectionDomain().getCodeSource();
			File sourceFile = new File(source.getLocation().getFile());
			ProcessBuilder pb;
			
			logger.info("In monitor");
			
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
				
				logger.info("Monitor started process " + process.pid());
				
				int status = process.waitFor();
				
				logger.info("Subprocess " + process.pid() + " exited with " + status);
				
				if (status != 0) {
					System.exit(status);
				}
				
			} catch (Exception exc) {
				logger.error("There was an exception starting the child " + exc.getMessage());
			}
			
		}
	}
		
	public static void start(String properties) {
		try {
			Configuration.getInstance().load(properties);
			
			logger.info("start with " + properties + " config = " + Configuration.getInstance());
			
			instance = new Server();
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
