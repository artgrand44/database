package org.art.db.server;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import org.apache.commons.io.IOUtils;
import org.art.db.client.ClientArgs;
import org.art.db.common.DBRuntimeException;
import org.art.db.common.DiscoveryHandler;
import org.art.db.common.DocumentSet;
import org.art.db.common.RestClient;
import org.art.db.common.Scheduler;
import org.art.db.common.TypedInputStream;
import org.art.db.common.TypedOutputStream;
import org.art.db.security.SecurityHandler;
import org.art.db.sql.SQLHandler;
import org.art.db.storage.BackupHandler;
import org.art.db.storage.Environment;
import org.art.db.storage.Files;
import org.art.db.storage.Metrics;
import org.art.db.storage.PrometheusPublisher;
import org.art.db.storage.RestoreManager;
import org.art.db.storage.StorageManager;
import org.art.db.ui.UIConsole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.undertow.server.HttpServerExchange;
import io.undertow.util.HeaderValues;

public class Dispatcher {

	private static final Logger logger = LoggerFactory.getLogger(Dispatcher.class);
	
	static interface Handler {
		Object handle(HttpServerExchange exchange, Map<String, String> parameters, byte[] body) throws Exception;
	}
	
	private static ObjectMapper MAPPER = new ObjectMapper();
	
	private static final Map<String, Handler> HANDLERS = new HashMap<>();
	
	static {
		   HANDLERS.put("/db/execute",          new ExecuteHandler());
		   HANDLERS.put("/db/executeQuery",     new ExecuteQueryHandler());
		   HANDLERS.put("/db/propagate",        new PropagateHandler());
		   HANDLERS.put("/db/ui",               new UIHandler());
		   HANDLERS.put("/db/wait",             new WaitInstanceCountHandler());
		   HANDLERS.put("/db/batch",            new BatchHandler());
		   HANDLERS.put("/db/fetch",            new FetchHandler());
		   HANDLERS.put("/db/log",              new LogHandler());
		   HANDLERS.put("/db/setproperty",      new SetPropertyHandler());
		   HANDLERS.put("/db/flush",            new FlushHandler());
		   HANDLERS.put("/db/close",            new CloseHandler());
		   HANDLERS.put("/db/getmetadata",      new GetMetadataHandler());
		   HANDLERS.put("/db/gettables",        new GetTablesHandler());
		   HANDLERS.put("/db/getfilenames",     new GetFileNamesHandler());
		   HANDLERS.put("/db/getfile",     		new GetFileHandler());
		   HANDLERS.put("/db/getproperties",    new GetPropertiesHandler());
		   HANDLERS.put("/db/stop" ,            new StopHandler());
		   HANDLERS.put("/db/ping" ,            new PingHandler());
		   HANDLERS.put("/db/backup" ,          new BackHandler());
		   HANDLERS.put("/db/restart",          new RestartHandler());
		   HANDLERS.put("/actuator/prometheus", new ScrapeHandler());
	}
	
	public static Object handle(HttpServerExchange exchange, String url, Map<String, String> parameters, byte[] body) throws Exception {
		
		if (url.startsWith("/db/resource")) {
			String resource = url.substring("/db/resource".length() + 1);
			
			try (InputStream stream = Server.class.getClassLoader().getResourceAsStream(resource)) {
				return IOUtils.toByteArray(stream);
			} 
		}
		
		Handler handler = HANDLERS.get(url);
		
		if (handler == null) {
			logger.info("Can't find handler " + url + " in " + HANDLERS.keySet());
			return "Hello".getBytes();
		}
		
		return handler.handle(exchange, parameters, body);

	}

	/**
	 * Check that an endpoint is being called by a server
	 * @param exchange
	 * @throws Exception
	 */
	private static void checkServer(HttpServerExchange exchange) throws Exception {
		HeaderValues authValues = exchange.getRequestHeaders().get("Authorization");
		
		if (authValues != null && authValues.size() != 0) {
			String authString = authValues.get(0);
			String auth = new String(Base64.getDecoder().decode(authString.substring("Basic ".length())));
			String[] tokens = auth.split(":");
			
			if (SecurityHandler.getInstance().isAdmin(tokens[0], tokens[1])) {
				return;
			} 			
		}
		
		
		HeaderValues clientValues = exchange.getRequestHeaders().get("Client");
		
		if (clientValues == null || clientValues.size() != 1) {
			throw new DBRuntimeException(exchange.getRequestURI() + " can only be invoked from database servers");
		}
		
		String client = clientValues.get(0);
		List<URI> peers = DiscoveryHandler.getServerInstance().getAllPeers();
		List<String> hosts = peers.stream().map(x -> x.getHost()).toList();
		
		if (!hosts.contains(client)) {
			DiscoveryHandler.getServerInstance().loadEnvironment();
			peers = DiscoveryHandler.getServerInstance().getAllPeers();
			hosts = peers.stream().map(x -> x.getHost()).toList();
		}
		
		if (!hosts.contains(client)) {
			logger.info("error on host " + DiscoveryHandler.getServerInstance().getLocalInstance() + " client = " + client + " hosts " + hosts);
			throw new DBRuntimeException(exchange.getRequestURI() + " can only be invoked from database servers");
		}	
	}
	
	public static class BatchHandler implements Handler {

		@Override
		public byte[] handle(HttpServerExchange exchange, Map<String, String> parameters, byte[] body) throws Exception {
			try (TypedInputStream input = new TypedInputStream(body); TypedOutputStream output = new TypedOutputStream()) {
				
				int count = input.readInt();
				
				for (int idx = 0; idx < count; idx++) {
					String uri = input.readUTF();
					byte[] request = new byte[input.readInt()];
					
					
					input.read(request);
					
					Handler handler = HANDLERS.get(uri);
					
					try {
						byte[] response = (byte[])handler.handle(exchange, parameters, request);

						output.writeBoolean(true);
						output.writeInt(response.length);
						output.write(response);
					} catch (Exception exc) {
						output.writeBoolean(false);
						output.writeUTF(exc.getMessage());
					}
				}
				
				return output.toByteArray();
			}
		}
		
	}
	
	private static String[] getAuthTokens(HttpServerExchange exchange) {
		HeaderValues headers = exchange.getRequestHeaders().get("Authorization");
		
		if (headers == null || headers.size() != 1) {
			return null;
		}
		
		String authToken = headers.get(0);
		String credentials = authToken.substring("Basic ".length()).trim();
		byte[] bytes = Base64.getDecoder().decode(credentials);
		String plainText = new String(bytes, Charset.defaultCharset());
		String[] tokens = plainText.split(":");
		
		return tokens.length == 2 ? tokens : null;
	}
	
	private static String getPassword(HttpServerExchange exchange) {
		String tokens[] =  getAuthTokens(exchange);
		
		return tokens == null ? "" : tokens[1];		
	}
	
	private static String getUser(HttpServerExchange exchange) {
		String tokens[] =  getAuthTokens(exchange);
		
		return tokens == null ? "" : tokens[0];		
	}
	
	static class BackHandler implements Handler {

		@Override
		public Object handle(HttpServerExchange exchange, Map<String, String> parameters, byte[] body) throws Exception {
			BackupHandler.getInstance().backup();
			return new byte[0];		
		}		
	}
	
	static class CloseHandler implements Handler {

		@Override
		public Object handle(HttpServerExchange exchange, Map<String, String> parameters, byte[] body) {
			logger.info("Closing tables");
			StorageManager.getInstance().closeAll();
			return new byte[0];
		}		
	}
	
	static class ExecuteHandler implements Handler {

		@SuppressWarnings("unchecked")
		@Override
		public Object handle(HttpServerExchange exchange, Map<String, String> parameters, byte[] body) throws Exception {
			if (!RestoreManager.getInstance().isRecovered()) {
				throw new DBRuntimeException("The instance is being recovered");
			}
			
			try (TypedInputStream stream = new TypedInputStream(body)) {
				ClientArgs args = new ClientArgs().deserialize(stream);
				List<Comparable<?>> parms = (List<Comparable<?>>)args.getParameters();
				long start = System.currentTimeMillis();
			
				SQLHandler.getInstance().execute(args.getShard(), args.getDatabase(), getUser(exchange), getPassword(exchange), args.getSql(), parms);
				
				HeaderValues values = exchange.getRequestHeaders().get("Client");
				String client = values == null || values.size() != 1 ? "unknown" : values.get(0);
					
				Metrics.getInstance().logClient(client, args.getSql(), System.currentTimeMillis() - start, 1, 1);
				
				return new byte[0];
			}
		}
		
	}
	
	static class ExecuteQueryHandler implements Handler {

		@SuppressWarnings("unchecked")
		@Override
		public Object handle(HttpServerExchange exchange, Map<String, String> parameters, byte[] body) throws Exception {
			if (!RestoreManager.getInstance().isRecovered()) {
				throw new DBRuntimeException("The instance is being recovered");
			}
			
			try (TypedInputStream inStream = new TypedInputStream(body)) {
				ClientArgs args = new ClientArgs().deserialize(inStream);
				List<Comparable<?>> parms = (List<Comparable<?>>)args.getParameters();
				long start = System.currentTimeMillis();
			
				DocumentSet set = SQLHandler.getInstance().executeQuery(args.getShard(), args.getDatabase(), getUser(exchange), getPassword(exchange), args.getSql(), parms);
				
				HeaderValues values = exchange.getRequestHeaders().get("Client");
				String client = values == null || values.size() != 1 ? "unknown" : values.get(0);
					
				Metrics.getInstance().logClient(client, args.getSql(), System.currentTimeMillis() - start, 1, set.size());
							
				try (TypedOutputStream stream = new TypedOutputStream()) {
					stream.writeObject(set);
					return stream.toByteArray();
				}
			} catch (Exception exc) {
				throw exc;
			}			
		}
		
	}
	
	static class FetchHandler implements Handler {

		@Override
		public Object handle(HttpServerExchange exchange, Map<String, String> parameters, byte[] body) throws Exception {
			String file = parameters.get("file");
			int index = Integer.parseInt(parameters.get("index"));
			int size = Integer.parseInt(parameters.get("size"));
			
			return DocumentSet.fetch(file, index, size);
		}
		
	}
	
	static class GetFileNamesHandler implements Handler {		
		@Override
		public Object handle(HttpServerExchange exchange, Map<String, String> parameters, byte[] body) throws Exception {
			checkServer(exchange);
			
			String table = parameters.get("table");
			String type = parameters.get("type");
			File[] files = Files.listFiles(table, type);
			
			if (files == null) {
				return "".getBytes();
			}
			
			List<String> names = new ArrayList<>();
			
			for (File file : files) {
				names.add(file.getName());
			}
			
			logger.info("getfilenames table = " + table + " type = " + type + " names " + names);
			
			return String.join(",", names).getBytes();
		}
	}

	static class GetTablesHandler implements Handler {
		
		@Override
		public Object handle(HttpServerExchange exchange, Map<String, String> parameters, byte[] body) throws Exception {
			return String.join(",", Files.listTables()).getBytes();
		}
	}

	
	static class GetMetadataHandler implements Handler {
		
		@Override
		public Object handle(HttpServerExchange exchange, Map<String, String> parameters, byte[] body) throws Exception {
			try {
				String table = parameters.get("table");
				File file = Files.getMetadata(table);

				return file.exists() ? file : new byte[0];
			} catch (Exception exc) {
				exc.printStackTrace();
				throw exc;
			}
		}

	}
	
	static class GetFileHandler implements Handler {
		
		@Override
		public Object handle(HttpServerExchange exchange, Map<String, String> parameters, byte[] body) throws Exception {
			checkServer(exchange);
			
			String table = parameters.get("table");
			String name = parameters.get("name");
			File file = new File(Files.getTableDirectory(table), name);

			logger.info("sending back " + file.getAbsolutePath() + "  " + file.length());
			return file.exists() ? file : null;
		}

	}
	
	static class GetPropertiesHandler implements Handler {
		
		@Override
		public Object handle(HttpServerExchange exchange, Map<String, String> parameters, byte[] body) throws Exception {
			String source = parameters.get("source");
			Map<String, String> result = Objects.equals(source, Environment.getInstance().getSource())
					                     ? Environment.getInstance().getDynamicProperties()
					                     : Collections.emptyMap();	 
			
			return MAPPER.writeValueAsBytes(result);
		}

	}
		
	static class LogHandler implements Handler {

		@Override
		public Object handle(HttpServerExchange exchange, Map<String, String> parameters, byte[] body) throws Exception {
			
			
			File file = new File("./serverlogs/serverlog.log");
			
			if (file.exists() && file.canRead()) {
				try (FileInputStream stream = new FileInputStream(file)) {
					return IOUtils.toByteArray(stream);
				}		
			}
			
			return new byte[0];
		}
	}

	static class PingHandler implements Handler {

		@Override
		public Object handle(HttpServerExchange exchange, Map<String, String> parameters, byte[] body) throws Exception {
			return "Hello".getBytes();
		}
	}

	static class PropagateHandler implements Handler {

		@Override
		public Object handle(HttpServerExchange exchange, Map<String, String> parameters, byte[] body) throws Exception {
			checkServer(exchange);
			
			try (TypedInputStream stream = new TypedInputStream(body)) {
				String table = stream.readUTF();
				
				Scheduler.DEFAULT.submit(() -> StorageManager.getInstance().getTableManager(table).applySnapshot(stream, true, null));
				
				return new byte[0];
			}
		}		
	}
	
	static class RestartHandler implements Handler {

		@Override
		public Object handle(HttpServerExchange exchange, Map<String, String> parameters, byte[] body) throws Exception {
			String target = parameters.get("target");
			URI localUri = DiscoveryHandler.getServerInstance().getLocalInstance();
			
			if (target.equals(localUri.getHost() + ":" + localUri.getPort())) {
				Scheduler.DEFAULT.schedule(() -> Server.exit(-1), 2, TimeUnit.SECONDS);
			} else {
				RestClient client = new RestClient();
				URI uri = client.buildURI(new URI("http://" + target), "/db/restart?target=" + target);
				
				client.get(uri);				
			}
			
			return new byte[0];
		}		
	}
	

	static class ScrapeHandler implements Handler {

		@Override
		public Object handle(HttpServerExchange exchange, Map<String, String> parameters, byte[] body) {
			return PrometheusPublisher.getInstance().scrape().getBytes();
		}		
	}
	
	static class UIHandler implements Handler {

		@Override
		public Object handle(HttpServerExchange exchange, Map<String, String> parameters, byte[] body) throws Exception {
			if (!SecurityHandler.getInstance().isAdmin(getUser(exchange), getPassword(exchange))) {
				throw new Server.UnathorizedException(404, "Not authorized");
			}
			
			String currentPage = parameters.get("currentPage");
			
			return UIConsole.getInstance().buildHtml(currentPage == null ? "" : currentPage, true).getBytes();
		}
					
	}

	static class SetPropertyHandler implements Handler {

		@Override
		public Object handle(HttpServerExchange exchange, Map<String, String> parameters, byte[] body) throws Exception {
			String[] params = new String(body).split("=");
			
			Environment.getInstance().setProperty(params[0], params[1]);
			
			return new byte[0];
		}
		
	}
	
	static class FlushHandler implements Handler {
		
		@Override
		public Object handle(HttpServerExchange exchange, Map<String, String> parameters, byte[] body) throws Exception {			
			try {
				String table = parameters.get("table");
				
				StorageManager.getInstance().getTableManager(table).flush();
				
				return new byte[0];
			} catch (Exception exc) {
				Metrics.getInstance().logAlert("There was an error flushing the database");
				throw exc;
			}
		}

	}
	
	
	static class StopHandler implements Handler {

		@Override
		public Object handle(HttpServerExchange exchange, Map<String, String> parameters, byte[] body) throws Exception {
			String target = parameters.get("target");
			URI localUri = DiscoveryHandler.getServerInstance().getLocalInstance();
			
			if (target.equals(localUri.getHost() + ":" + localUri.getPort())) {
				Scheduler.DEFAULT.schedule(() -> Server.exit(0), 2, TimeUnit.SECONDS);
			} else {
				RestClient client = new RestClient();
				URI uri = client.buildURI(new URI("http://" + target), "/db/stop?target=" + target);
				
				client.get(uri);				
			}
			
			return new byte[0];		
		}		
	}
	
		
	static class WaitInstanceCountHandler implements Handler {

		@Override
		public Object handle(HttpServerExchange exchange, Map<String, String> parameters, byte[] body) throws Exception {
			DiscoveryHandler handler = DiscoveryHandler.getServerInstance();
			int count = Integer.parseInt(parameters.get("count"));
			
			if (handler != null) {
				handler.waitInstanceCount(count, 60 * 1000);
			}
			
			return new byte[0];
		}
	}

	
}
