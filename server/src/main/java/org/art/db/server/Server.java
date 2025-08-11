package org.art.db.server;

import java.io.File;
import java.io.FileInputStream;
import java.net.InetAddress;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock.ReadLock;
import java.util.concurrent.locks.ReentrantReadWriteLock.WriteLock;

import org.apache.commons.io.IOUtils;
import org.art.db.common.ConnectionInfo;
import org.art.db.common.DiscoveryHandler;
import org.art.db.common.InstanceRecoveringException;
import org.art.db.common.RestException;
import org.art.db.common.Utils;
import org.art.db.common.ZipUtils;
import org.art.db.security.SecurityHandler;
import org.art.db.storage.BackupManager;
import org.art.db.storage.Broadcaster;
import org.art.db.storage.DBFiles;
import org.art.db.storage.Environment;
import org.art.db.storage.MetricsLogger;
import org.art.db.storage.PrometheusLogger;
import org.art.db.storage.RecoveryManager;
import org.art.db.storage.RollingAverage;
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
		ConnectionInfo info = Utils.isKubernetes() ? ConnectionInfo.fromKubernetes() : ConnectionInfo.fromProperties(properties);
		
		this.handler =  new DiscoveryHandler(info, true);
		this.port = Integer.valueOf(this.handler.getLocalPort());
				
		try {
			SecurityHandler.getInstance().setEnabled(true);
			
			if (Environment.getInstance().getPropertyWithDefault("org.art.db.metricslogger").equalsIgnoreCase("console")) {
				MetricsLogger.setInstance(UIConsole.getInstance());
			}
				
			MetricsLogger.getInstance().start(info);
			MetricsLogger.getInstance().logAlert("Starting");
						
			BackupManager.getInstance().start();
			this.handler.start();
			
			
			InetAddress address = InetAddress.getLocalHost();
			
			this.server = Undertow.builder()
								.setByteBufferPool(new DefaultByteBufferPool(false, 1024, 1024 * 1000, 10))
								.addHttpListener(port, address.getHostAddress(), this)
								.setServerOption(UndertowOptions.ENABLE_HTTP2, true)
								.setServerOption(UndertowOptions.SHUTDOWN_TIMEOUT, 2000)
								.build();
			
			this.server.start();
			
			RecoveryManager.getInstance().recover();
			MetricsLogger.getInstance().logStatus("Running");
		} catch (Throwable exc) {
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
			HeaderValues headers = exchange.getRequestHeaders().get("Content-Encoding");
			boolean zip = headers != null && headers.contains("gzip");
			
			if (response instanceof byte[] bytes) {
				if (zip) {
					bytes = ZipUtils.compress(bytes);
					exchange.getResponseHeaders().add(new HttpString("Content-Encoding"), "gzip");					
				}
				
				exchange.getOutputStream().write(bytes);
			} else if (response != null){
				File file = (File)response;
				File tempFile = DBFiles.changeExtention(file, ".zip");
				
				exchange.getResponseHeaders().add(new HttpString("filename"), file.getName());
				
				ZipUtils.compress(file, tempFile);
				
				try (FileInputStream inputStream = new FileInputStream(tempFile)) {
					IOUtils.copyLarge(inputStream, exchange.getOutputStream());
				}
				
				tempFile.delete();
			}			
		} catch (UnathorizedException exc) {
			exchange.getResponseHeaders().put(Headers.WWW_AUTHENTICATE, "Basic realm=Database");
			exchange.setStatusCode(RestException.UNAUTHORIZED);
		} catch (InstanceRecoveringException exc) {
			exchange.setStatusCode(RestException.SERVER_DOWN);
			exchange.getResponseHeaders().add(new HttpString("warning"), "The instance is recovering");	
		} catch (Exception exc) {
			logger.info("Exception " + exc.getMessage());
			exchange.setStatusCode(RestException.TEA_POT);
			String warning = String.format("Warning: 199 - %s", exc.getMessage());

			exchange.getResponseHeaders().add(new HttpString("warning"), warning);	
		} finally {
			this.readLock.unlock();
		}
		
	}
	
	public static void main(String[] args) {
		String properties = args.length == 0 ? "application.properties" : args[0];
					
		start(properties);
		
    }
	
			
	public static void start(String properties) {
		try {
			Environment.getInstance().load(properties);
			instance = new Server(properties);
		} catch (Exception exc) {
			throw new IllegalStateException(exc);
		}		
	}
	
	public static void stop() {		
		if (instance != null) {
			MetricsLogger.getInstance().logStatus("Stopping");
			instance.server.stop();
			StorageManager.getInstance().closeAll();
			instance.handler.stop();
			DiscoveryHandler.clear();
			UIConsole.getInstance().stop();	
			BackupManager.getInstance().stop();
			Broadcaster.getInstance().stop();
			PrometheusLogger.getInstance().stop();
			RollingAverage.stop();
			instance = null;
		}
	}
	
	static class UnathorizedException extends RestException {

		private static final long serialVersionUID = 1L;

		public UnathorizedException(int status, String warning) {
			super(status, warning);
		}
		
	}	
	
}
