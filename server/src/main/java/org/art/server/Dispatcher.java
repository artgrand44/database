package org.art.server;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.art.db.common.Configuration;
import org.art.db.common.DiscoveryHandler;
import org.art.db.common.ServerDocumentSet;
import org.art.security.SecurityHandler;
import org.art.sql.SQLHandler;
import org.art.storage.StorageManager;
import org.art.db.common.TypedInputStream;
import org.art.db.common.TypedOutputStream;
import org.art.ui.UIConsole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.undertow.server.HttpServerExchange;
import io.undertow.util.HeaderValues;

public class Dispatcher {

	private static final Logger logger = LoggerFactory.getLogger(Dispatcher.class);
	
	static interface Handler {
		byte[] handle(HttpServerExchange exchange, Map<String, String> parameters, byte[] body) throws Exception;
	}
	
	private static final Map<String, Handler> HANDLERS = 
			Map.of("/db/execute",      new ExecuteHandler(),
				   "/db/executeQuery", new ExecuteQueryHandler(),
				   "/db/propagate",    new PropagateHandler(),
				   "/db/ui",           new UIHandler(),
				   "/db/wait",         new WaitInstanceCountHandler(),
				   "/db/fetch",        new FetchHandler(),
				   "/db/log",          new LogHandler(),
				   "/db/setproperty",  new SetPropertyHandler());
	
	public static byte[] handle(HttpServerExchange exchange, String url, Map<String, String> parameters, byte[] body) throws Exception {
		
		if (url.startsWith("/db/resource")) {
			String resource = url.substring("/db/resource".length() + 1);
			
			try (InputStream stream = Server.class.getClassLoader().getResourceAsStream(resource)) {
				return IOUtils.toByteArray(stream);
			} 
		}
		
		Handler handler = HANDLERS.get(url);
		
		if (handler == null) {
			logger.info("Can't find handler " + url + " in " + HANDLERS);
			return new byte[0];
		}
		
		return handler.handle(exchange, parameters, body);

	}

	
	static class ExecuteHandler implements Handler {

		@SuppressWarnings("unchecked")
		@Override
		public byte[] handle(HttpServerExchange exchange, Map<String, String> parameters, byte[] body) throws Exception {
			try (TypedInputStream stream = new TypedInputStream(body)) {
				String database = (String)stream.readObject();
				String shard = (String)stream.readObject();
				String user = (String)stream.readObject();
				String password = (String)stream.readObject();
				String sql = (String)stream.readObject();
				List<Comparable<?>> parameterList = (List<Comparable<?>>)stream.readObject();
				long start = System.currentTimeMillis();
			
				SQLHandler.getInstance().execute(shard, database, user, password, sql, parameterList);
				
				UIConsole console = UIConsole.getCurrent();
				
				if (console != null) {
					HeaderValues values = exchange.getRequestHeaders().get("Client");
					String client = values == null || values.size() != 1 ? "unknown" : values.get(0);
					
					console.logClient(client, sql, System.currentTimeMillis() - start, 1, 1);
				}
			
				return new byte[0];
			}
		}
		
	}
	
	static class ExecuteQueryHandler implements Handler {

		@SuppressWarnings("unchecked")
		@Override
		public byte[] handle(HttpServerExchange exchange, Map<String, String> parameters, byte[] body) throws Exception {
			try (TypedInputStream inStream = new TypedInputStream(body)) {
				String database = (String)inStream.readObject();
				String shard = (String)inStream.readObject();
				String user = (String)inStream.readObject();
				String password = (String)inStream.readObject();
				String sql = (String)inStream.readObject();
				List<Comparable<?>> parameterList = (List<Comparable<?>>)inStream.readObject();
				long start = System.currentTimeMillis();
			
				ServerDocumentSet set = SQLHandler.getInstance().executeQuery(shard, database, user, password, sql, parameterList);
				
				UIConsole console = UIConsole.getCurrent();
				
				if (console != null) {
					HeaderValues values = exchange.getRequestHeaders().get("Client");
					String client = values == null || values.size() != 1 ? "unknown" : values.get(0);
					
					console.logClient(client, sql, System.currentTimeMillis() - start, 1, set.size());
				}
			
				try (TypedOutputStream stream = new TypedOutputStream()) {
					stream.writeObject(set);
					return stream.toByteArray();
				}
			} catch (Exception exc) {
				exc.printStackTrace();
				throw exc;
			}
		}
		
	}
	
	static class FetchHandler implements Handler {

		@Override
		public byte[] handle(HttpServerExchange exchange, Map<String, String> parameters, byte[] body) throws Exception {
			String file = parameters.get("file");
			int index = Integer.parseInt(parameters.get("index"));
			int size = Integer.parseInt(parameters.get("size"));
			
			return ServerDocumentSet.fetch(file, index, size);
		}
		
	}

	static class LogHandler implements Handler {

		@Override
		public byte[] handle(HttpServerExchange exchange, Map<String, String> parameters, byte[] body) throws Exception {
			
			
			File file = new File("./serverlogs/serverlog.log");
			
			if (file.exists() && file.canRead()) {
				try (FileInputStream stream = new FileInputStream(file)) {
					return IOUtils.toByteArray(stream);
				}		
			}
			
			return new byte[0];
		}
	}

	static class PropagateHandler implements Handler {

		@Override
		public byte[] handle(HttpServerExchange exchange, Map<String, String> parameters, byte[] body) throws Exception {
			try (TypedInputStream stream = new TypedInputStream(body)) {
				String table = stream.readUTF();
				
				StorageManager.getInstance().getTableManager(table).applySnapshot(stream, true);
			
				return new byte[0];
			}
		}
		
	}
	
	static class UIHandler implements Handler {

		@Override
		public byte[] handle(HttpServerExchange exchange, Map<String, String> parameters, byte[] body) throws Exception {
			HeaderValues authHeader = exchange.getRequestHeaders().get("Authorization");
			String authToken = authHeader == null || authHeader.size() != 1 ? "" : authHeader.get(0);
			
			if (StringUtils.isAllBlank(authToken)) {
				throw new Server.UnathorizedException(404, "Not authorized");
			}
			
			String credentials = authToken.substring("Basic ".length()).trim();
			byte[] bytes = Base64.getDecoder().decode(credentials);
			String plainText = new String(bytes, Charset.defaultCharset());
			String[] tokens = plainText.split(":");
			
			if (!SecurityHandler.getInstance().isAdmin(tokens[0], tokens[1])) {
				throw new Server.UnathorizedException(404, "Not authorized");
			}
			
			String currentPage = parameters.get("currentPage");
			
			return UIConsole.getCurrent().buildHtml(currentPage == null ? "" : currentPage, true).getBytes();
		}
					
	}

	static class SetPropertyHandler implements Handler {

		@Override
		public byte[] handle(HttpServerExchange exchange, Map<String, String> parameters, byte[] body) throws Exception {
			String[] params = new String(body).split("=");
			
			Configuration.getInstance().setProperty(params[0], params[1]);
			
			return new byte[0];
		}
		
	}
	static class WaitInstanceCountHandler implements Handler {

		@Override
		public byte[] handle(HttpServerExchange exchange, Map<String, String> parameters, byte[] body) throws Exception {
			DiscoveryHandler handler = DiscoveryHandler.getServerInstance();
			int count = Integer.parseInt(parameters.get("count"));
			
			if (handler != null) {
				handler.waitInstanceCount(count, 60 * 1000);
			}
			
			return new byte[0];
		}
	}

	
}
