package org.art.db.server;

import java.net.InetAddress;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

import org.art.db.common.Configuration;
import org.art.db.common.DiscoveryHandler;
import org.art.db.common.RestException;
import org.art.db.common.ZipUtils;
import org.art.db.security.SecurityHandler;
import org.art.db.storage.StorageManager;
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
	
	public Server(DiscoveryHandler handler) {
		this.port = Integer.valueOf(Configuration.getInstance().getPort());
		this.handler = handler;
		
		try {
			SecurityHandler.getInstance().setEnabled(true);
			
			this.handler.start();
			
			InetAddress address = InetAddress.getLocalHost();

			this.server = Undertow.builder()
								.setByteBufferPool(new DefaultByteBufferPool(false, 1024, 1024 * 1000, 10))
								.addHttpListener(port, address.getHostAddress(), this)
								.setServerOption(UndertowOptions.ENABLE_HTTP2, true)
								.setServerOption(UndertowOptions.SHUTDOWN_TIMEOUT, 2000)
								.build();
			
			this.server.start();
			logger.info("Started WebServer on port " + port);
		} catch (Throwable exc) {
			logger.error(exc.getClass().getName() + exc.getMessage());
		}		
	}
	
	@Override
	public void handleRequest(HttpServerExchange exchange) throws Exception {
		if (exchange.isInIoThread()) {
			exchange.dispatch(this);
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
			Dispatcher.handle(exchange, exchange.getRequestURI(), params, message);
		} catch (UnathorizedException exc) {
			exchange.getResponseHeaders().put(Headers.WWW_AUTHENTICATE, "Basic realm=Cache");
			exchange.setStatusCode(RestException.UNAUTHORIZED);
		} catch (Exception exc) {
			exchange.setStatusCode(RestException.TEA_POT);
			
			String warning = String.format("Warning: 199 - %s", exc.getMessage());
				
			exchange.getResponseHeaders().add(new HttpString("warning"), warning);	
		}
		
	}
	
	public static void main(String[] args) {
        start(args.length == 0 ? ".application.properties" : args[0]);
    }
	
	
	public static void start(String properties) {
		try {
			Configuration.getInstance().load(properties);
			
			logger.info("start with " + properties + " config = " + Configuration.getInstance());
			
			StorageManager.getInstance().recover();
			
			instance = new Server(new DiscoveryHandler(Configuration.getInstance(), true));
		} catch (Exception exc) {
			throw new IllegalStateException(exc);
		}		
	}
	
	public static void stop() {		
		if (instance != null) {
			instance.server.stop();
			instance.handler.stop();
			DiscoveryHandler.clear();
			StorageManager.getInstance().closeAll();		
		}
	}
	
	static class UnathorizedException extends RestException {

		private static final long serialVersionUID = 1L;

		public UnathorizedException(int status, String warning) {
			super(status, warning);
		}
		
	}

	
	
}
