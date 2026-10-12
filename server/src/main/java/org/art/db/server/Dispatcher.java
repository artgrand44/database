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

import org.apache.commons.io.IOUtils;
import org.art.db.common.Batch;
import org.art.db.common.ClientArgs;
import org.art.db.common.DBRuntimeException;
import org.art.db.common.DiscoveryHandler;
import org.art.db.common.DocumentSet;
import org.art.db.common.InstanceRecoveringException;
import org.art.db.common.Scheduler;
import org.art.db.common.TypedInputStream;
import org.art.db.common.TypedOutputStream;
import org.art.db.common.Utils;
import org.art.db.security.SecurityHandler;
import org.art.db.sql.SQLHandler;
import org.art.db.storage.BackupManager;
import org.art.db.storage.Environment;
import org.art.db.storage.DBFiles;
import org.art.db.storage.LogOutputStream;
import org.art.db.storage.MetricsLogger;
import org.art.db.storage.PrometheusLogger;
import org.art.db.storage.RecoveryManager;
import org.art.db.storage.StorageManager;
import org.art.db.ui.UIConsole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.vafer.jdeb.shaded.commons.io.FileUtils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.TypeFactory;
import com.fasterxml.jackson.dataformat.cbor.databind.CBORMapper;

import io.undertow.server.HttpServerExchange;
import io.undertow.util.HeaderValues;

public class Dispatcher {

	private static final Logger logger = LoggerFactory.getLogger(Dispatcher.class);
	
	static interface Handler {
		Object handle(HttpServerExchange exchange, Map<String, String> parameters, byte[] body) throws Exception;
	}
	
	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final CBORMapper  CBOR_MAPPER = new CBORMapper();
	
	private static final Map<String, Handler> HANDLERS = new HashMap<>();
	
	static {
		   HANDLERS.put("/db/execute",          new ExecuteHandler());
		   HANDLERS.put("/db/executeBatch",     new ExecuteBatchHandler());
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
		   HANDLERS.put("/db/getnextfile",     	new GetNextFileHandler());
		   HANDLERS.put("/db/getproperties",    new GetPropertiesHandler());
		   HANDLERS.put("/db/ping" ,            new PingHandler());
		   HANDLERS.put("/db/backup" ,          new BackupHandler());
		   HANDLERS.put("/db/beginstartup",     new BeginStartupHandler());
		   HANDLERS.put("/metrics",             new MetricsHandler());
		   HANDLERS.put("/db/getstats",         new GetStatsHandler());
		   HANDLERS.put("/db/reset",            new ResetHandler());
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
		
		LogOutputStream.startBatch();
		
		try {
			return handler.handle(exchange, parameters, body);
		} finally {
			LogOutputStream.endBatch();
		}

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
	
	private static <T> T deserialize(String format, byte[] bytes, Class<T> clazz) throws Exception {
		
		if (format == null || format.isEmpty()) {
			try (TypedInputStream stream = new TypedInputStream(bytes)) {
				Object obj = stream.readObject();
				
				try {
					return clazz.cast(obj);
				} catch (Exception exc) {
					logger.info("deserialize exception clazz = " + clazz.getSimpleName() + " obj " + obj.getClass().getSimpleName());
					throw exc;
				}
			} 
		} else {
			return CBOR_MAPPER.readValue(bytes, clazz);
		}		
	}
	
	@SuppressWarnings("unchecked")
	private static <T> List<T> deserializeList(String format, byte[] bytes, Class<T> clazz) throws Exception {
		if (format == null || format.isEmpty()) {
			try (TypedInputStream stream = new TypedInputStream(bytes)) {
				return (List<T>)(stream.readObject());
			}
		} else {
			return CBOR_MAPPER.readValue(bytes, TypeFactory.defaultInstance().constructCollectionLikeType(ArrayList.class, clazz));
		}		
	}
	
	private static byte[] serialize(String format, Object value) throws Exception {
		if (format == null || format.isEmpty()) {
			try (TypedOutputStream stream = new TypedOutputStream()) {
				stream.writeObject(value);
				return stream.toByteArray();
			}
		} else {
			return CBOR_MAPPER.writeValueAsBytes(value);
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
	
	static class BackupHandler implements Handler {

		@Override
		public Object handle(HttpServerExchange exchange, Map<String, String> parameters, byte[] body) throws Exception {
			BackupManager.getInstance().backup();
			return new byte[0];		
		}		
	}
	
	static class BeginStartupHandler implements Handler {

		@Override
		public Object handle(HttpServerExchange exchange, Map<String, String> parameters, byte[] body) throws Exception {
			String instance = parameters.get("instance");
			URI uri = new URI(instance);
			DiscoveryHandler handler = DiscoveryHandler.getServerInstance();
			
			logger.info(handler.getLocalInstance() + " got begin from " + uri);
			while (!handler.getLocalPeers().contains(uri)) {
				Utils.sleepSafely(100);
				handler.loadEnvironment();
			}
			
			return new byte[0];
		}		
	}
	
	static class CloseHandler implements Handler {

		@Override
		public Object handle(HttpServerExchange exchange, Map<String, String> parameters, byte[] body) {
			StorageManager.getInstance().closeAll();
			return new byte[0];
		}		
	}
	
	
	static class ExecuteHandler implements Handler {

		@SuppressWarnings("unchecked")
		@Override
		public Object handle(HttpServerExchange exchange, Map<String, String> parameters, byte[] body) throws Exception {
			if (!RecoveryManager.getInstance().isRecovered()) {
				throw new InstanceRecoveringException();
			}
						
			ClientArgs args = deserialize(parameters.get("format"), body, ClientArgs.class);
			List<Comparable<?>> parms = (List<Comparable<?>>)args.getParameters();
			long start = System.currentTimeMillis();

			SQLHandler.getInstance().execute(args.getShard(), args.getDatabase(), getUser(exchange), getPassword(exchange), args.getSql(), parms);

			HeaderValues values = exchange.getRequestHeaders().get("Client");
			String client = values == null || values.size() != 1 ? "unknown" : values.get(0);

			MetricsLogger.getInstance().logClient(client, args.getSql(), System.currentTimeMillis() - start, 1, 1);

			return new byte[0];

		}
		
	}
	
	static class ExecuteBatchHandler implements Handler {

		@SuppressWarnings("unchecked")
		@Override
		public Object handle(HttpServerExchange exchange, Map<String, String> parameters, byte[] body) throws Exception {
			if (!RecoveryManager.getInstance().isRecovered()) {
				throw new InstanceRecoveringException();
			}
			
			Batch batch = deserialize(parameters.get("format"), body, Batch.class);
			
			for (ClientArgs args : batch.getClientArgs()) {
				List<Comparable<?>> parms = (List<Comparable<?>>)args.getParameters();
				long start = System.currentTimeMillis();

				SQLHandler.getInstance().execute(args.getShard(), args.getDatabase(), getUser(exchange), getPassword(exchange), args.getSql(), parms);

				HeaderValues values = exchange.getRequestHeaders().get("Client");
				String client = values == null || values.size() != 1 ? "unknown" : values.get(0);

				MetricsLogger.getInstance().logClient(client, args.getSql(), System.currentTimeMillis() - start, 1, 1);
			}

			return new byte[0];
		}
	}

	static class ExecuteQueryHandler implements Handler {

		@SuppressWarnings("unchecked")
		@Override
		public Object handle(HttpServerExchange exchange, Map<String, String> parameters, byte[] body) throws Exception {
			if (!RecoveryManager.getInstance().isRecovered()) {
				throw new InstanceRecoveringException();
			}
						
			ClientArgs args = deserialize(parameters.get("format"), body, ClientArgs.class);
			List<Comparable<?>> parms = (List<Comparable<?>>)args.getParameters();
			long start = System.currentTimeMillis();

			DocumentSet set = SQLHandler.getInstance().executeQuery(args.getShard(), args.getDatabase(), getUser(exchange), getPassword(exchange), args.getSql(), parms);

			HeaderValues values = exchange.getRequestHeaders().get("Client");
			String client = values == null || values.size() != 1 ? "unknown" : values.get(0);

			MetricsLogger.getInstance().logClient(client, args.getSql(), System.currentTimeMillis() - start, 1, set.size());

			return serialize(parameters.get("format"), set);					
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
			File[] files = DBFiles.listFiles(table, type);
			
			logger.info(DiscoveryHandler.getServerName() + " table " + table + " type " + type + " count " + files.length);
			
			List<String> names = new ArrayList<>();
			
			for (File file : files) {
				if (file.length() != 0) {
					names.add(file.getName());
					logger.info("added " + file.getName());
				} else {
					logger.info("Zero length for " + file.getAbsolutePath());
				}
			}
			
			return String.join(",", names).getBytes();
		}
	}

	static class GetTablesHandler implements Handler {
		
		@Override
		public Object handle(HttpServerExchange exchange, Map<String, String> parameters, byte[] body) throws Exception {
			return String.join(",", DBFiles.listTables()).getBytes();
		}
	}

	
	static class GetMetadataHandler implements Handler {
		
		@Override
		public Object handle(HttpServerExchange exchange, Map<String, String> parameters, byte[] body) throws Exception {
			try {
				String table = parameters.get("table");
				File file = DBFiles.getMetadata(table);

				return file.exists() ? file : new byte[0];
			} catch (Exception exc) {
				exc.printStackTrace();
				throw exc;
			}
		}

	}
	
	static class GetNextFileHandler implements Handler {
		
		@Override
		public Object handle(HttpServerExchange exchange, Map<String, String> parameters, byte[] body) throws Exception {
			checkServer(exchange);
			
			checkServer(exchange);
			
			String table = parameters.get("table");
			String type = parameters.get("type");
			long timestamp = Long.parseLong(parameters.get("timestamp"));
			File[] array = DBFiles.listFiles(table, type);
			
			if (array == null) {
				logger.info("getnext no files");
				return null;
			}
			
			List<File> files = new ArrayList<>();
			
			for (File file : array) {
				if (file.length() != 0) {
					files.add(file);
				}
			}
			
			Collections.sort(files, (x, y) -> (int)(getTimestamp(x) - getTimestamp(y)));
			
			for (File file : files) {
				if (getTimestamp(file) > timestamp && file.length() != 0) {
					return file;
				}
			}
			
			return null;
		}

		private long getTimestamp(File file) {
			String name = file.getName();
			int dash = name.indexOf('_');
			
			return Long.valueOf(name.substring(0, dash));
		}
		
	}

	static class GetFileHandler implements Handler {
		
		@Override
		public Object handle(HttpServerExchange exchange, Map<String, String> parameters, byte[] body) throws Exception {
			checkServer(exchange);
			
			String table = parameters.get("table");
			String name = parameters.get("name");
			File file = new File(DBFiles.getTableDirectory(table), name);

			logger.info("send back " + file.getAbsolutePath() + " " + file.length() + " " + file.exists());
			return file.exists() ? file : null;
		}

	}
	
	static class GetStatsHandler implements Handler {

		@Override
		public Object handle(HttpServerExchange exchange, Map<String, String> parameters, byte[] body) throws Exception{
			return UIConsole.getInstance().getStatsAsJson();
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
				String table = parameters.get("table");
				
				logger.info("prop " + table + " " + DiscoveryHandler.getServerInstance().getLocalPort());
				
				Scheduler.DEFAULT.submit(() -> StorageManager.getInstance().getTableManager(table).applySnapshot(stream, true, null));
				
				return new byte[0];
			}
		}		
	}
	
	static class MetricsHandler implements Handler {

		@Override
		public Object handle(HttpServerExchange exchange, Map<String, String> parameters, byte[] body) {
			return PrometheusLogger.getInstance().scrape().getBytes();
		}		
	}
	
	static class UIHandler implements Handler {

		@Override
		public Object handle(HttpServerExchange exchange, Map<String, String> parameters, byte[] body) throws Exception {
			if (!SecurityHandler.getInstance().isAdmin(getUser(exchange), getPassword(exchange))) {
				throw new Server.UnathorizedException(404, "Not authorized");
			}
			
			String currentPage = parameters.get("currentPage");
			
			return UIConsole.getInstance().buildHtml(currentPage == null ? "" : currentPage).getBytes();
		}
					
	}

	static class SetPropertyHandler implements Handler {

		@Override
		public Object handle(HttpServerExchange exchange, Map<String, String> parameters, byte[] body) throws Exception {
			String[] params = new String(body).split("=");
			
			Environment.getInstance().setProperty(params[0], params[1]);
			Environment.getInstance().save();
			
			return new byte[0];
		}
		
	}
	
	static class ResetHandler implements Handler {

		@Override
		public Object handle(HttpServerExchange exchange, Map<String, String> parameters, byte[] body) throws Exception {
			if (parameters.get("backup").equalsIgnoreCase("true")) {logger.info("Cleaning backups");
				FileUtils.cleanDirectory(DBFiles.getBackupRoot());
			}
			
			FileUtils.cleanDirectory(DBFiles.getRoot());
			
			File[] files = DBFiles.getRoot().listFiles();
			
			if (files != null && files.length != 0) {
				logger.info("Unable to clear files");
				throw new Exception("Clear files failed");
			}
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
				MetricsLogger.getInstance().logAlert("There was an error flushing the database");
				throw exc;
			}
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
			
			while (!RecoveryManager.getInstance().isRecovered()) {
				Utils.sleepSafely(1000);
			}
			
			return new byte[0];
		}
	}

	
}
