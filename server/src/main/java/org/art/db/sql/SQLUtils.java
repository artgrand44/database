package org.art.db.sql;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.art.db.client.Client;
import org.art.db.common.DiscoveryHandler;
import org.art.db.common.Document;
import org.art.db.common.Key;
import org.art.db.common.DocumentSet;
import org.art.db.query.Filter;
import org.art.db.storage.Comparison;
import org.art.db.storage.Metadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.schema.Table;

public class SQLUtils {

	private static final Logger logger = LoggerFactory.getLogger(SQLUtils.class);
	
	static void broadcastExecute(String database, String statement, String user, String password, List<Comparable<?>> parameters) throws Exception {
		DiscoveryHandler handler = DiscoveryHandler.getServerInstance();
		
		if (handler != null) {
			for (String shard : handler.getInstances().keySet()) {
				if (!shard.equals(handler.getlocalShard())) {
					forwardExecute(database, shard, statement, user, password, parameters);
				}
			}
		}
	}
	
	static void broadcastAll(String database, String statement, String user, String password) throws Exception {
		DiscoveryHandler handler = DiscoveryHandler.getServerInstance();
		
		if (handler != null) {
			URI local = handler.getLocalInstance();
			
			for (Map.Entry<String, List<URI>> entry : handler.getInstances().entrySet()) {
				String shard = entry.getKey();

				for (URI uri : entry.getValue()) {
					if (!uri.equals(local)) {
						Client client = new Client(handler, uri, shard, user, password);
						
						client.execute(statement);
					}
				}
			}		
		}
	}
	
	static DocumentSet broadcastExecuteQuery(String database, String statement, String user, String password, List<Comparable<?>> parameters) throws Exception {
		DocumentSet set = new DocumentSet();
		DiscoveryHandler handler = DiscoveryHandler.getServerInstance();

		if (handler != null) {
			for (String shard : handler.getInstances().keySet()) {
				if (!shard.equals(handler.getlocalShard())) {
					try {
						set.addAll(forwardExecuteQuery(database, shard, statement, user, password, parameters));
					} catch (Exception exc) {
						logger.info("  forward on handler.getLocalPort() got exception " + exc.getMessage()); 
					}
				}
			}
		}
		
		return set;
	}
	
	private static Filter findFilter(List<Filter> filters, String name) {
		for (Filter filter : filters) {
			if (filter.getField().equals(name)) {
				return filter;
			}
		}
			
		return null;
	}
	
	static void forwardExecute(String database, String shard, String statement, String user, String password, List<Comparable<?>> parameters) throws Exception {
		DiscoveryHandler handler = DiscoveryHandler.getServerInstance();
		
		if (handler != null) {
			Client client = new Client(handler, shard, user, password);
			
			client.use(database);
			client.execute(statement, parameters.toArray());			
		}
	}
	
	static DocumentSet forwardExecuteQuery(String database, String shard, String statement, String user, String password, List<Comparable<?>> parameters) throws Exception {
		DocumentSet set = new DocumentSet();
		DiscoveryHandler handler = DiscoveryHandler.getServerInstance();
		
		if (handler != null) {
			Client client = new Client(handler, shard, user, password);
			
			client.use(database);
			set.addAll(client.executeQuery(statement, parameters.toArray()));
		}
		
		return set;
	}
	

	static String getFQN(Table table, String database) {
		String dbName = table.getDatabase().getDatabaseName();
		String schema = table.getSchemaName();
		String name = table.getName();
		
		if (StringUtils.isBlank(dbName)) {
			dbName = StringUtils.isBlank(database) ? "SYS" : database;
		}
		
		if (StringUtils.isBlank(schema)) {
			schema = "SYS";
		}
		
		return String.format("%s.%s.%s", dbName, schema, name);		
	}
	
	static String getFQN(String table, String database) {
		String dbName = StringUtils.isBlank(database) ? "SYS" : database;
		
		return String.format("%s.%s.%s", dbName, "SYS", table);		
	}
	static String getShard(String table, Document doc) throws Exception {
		DiscoveryHandler handler = DiscoveryHandler.getServerInstance();
		
		if (handler == null) {
			return Client.WILDCARD;
		}
		
		Metadata metadata = Metadata.getMetadata(table);
		List<Comparable<?>> keys = new ArrayList<>();
		
		for (String field : metadata.primaryKeyFields()) {
			keys.add((Comparable<?>)doc.get(field));
		}
		
		return handler.getShard(new Key(keys).toString());
			
	}
	
	static String getShard(String table, Expression where, List<Comparable<?>> parameters) throws Exception {
		DiscoveryHandler handler = DiscoveryHandler.getServerInstance();
				
		if (handler == null || ! (where instanceof FilterExpression)) {
			return Client.WILDCARD;
		}
		
		FilterExpression fexp = (FilterExpression) where;
		

		Metadata metadata = Metadata.getMetadata(table);
		List<Filter> filters = fexp.getFilters();
		List<Comparable<?>> keys = new ArrayList<>();
		ExpressionEvaluator evaluator = new ExpressionEvaluator(table);

		for (String field : metadata.primaryKeyFields()) {
			Filter filter = findFilter(filters, field);

			if (filter != null && filter.getComparison() == Comparison.EQ) {
				Object fieldValue = evaluator.evaluate((Expression)filter.getValue(), (Document)null, parameters); 

				keys.add((Comparable<?>)fieldValue);
			} else {
				return null;
			}
		}

		return handler.getShard(new Key(keys).toString());
	
	}
	
	static boolean isShardLocal(String shard) {
		DiscoveryHandler handler = DiscoveryHandler.getServerInstance();
		
		return handler == null || shard.equals(handler.getlocalShard()) || shard.equals(Client.WILDCARD);
	}


	
}
