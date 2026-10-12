package org.art.db.sql;

import java.io.File;
import java.sql.Date;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

import org.apache.commons.io.FileUtils;
import org.art.db.client.Client;
import org.art.db.common.Converter;
import org.art.db.common.DBRuntimeException;
import org.art.db.common.Document;
import org.art.db.common.GUID;
import org.art.db.security.SecurityHandler;
import org.art.db.security.User;
import org.art.db.storage.ColumnInfo;
import org.art.db.storage.DBFiles;
import org.art.db.storage.IndexInfo;
import org.art.db.storage.Metadata;
import org.art.db.storage.StorageManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.alter.Alter;
import net.sf.jsqlparser.statement.alter.AlterExpression;
import net.sf.jsqlparser.statement.alter.AlterExpression.ColumnDataType;
import net.sf.jsqlparser.statement.create.index.CreateIndex;
import net.sf.jsqlparser.statement.create.table.ColumnDefinition;
import net.sf.jsqlparser.statement.create.table.CreateTable;
import net.sf.jsqlparser.statement.create.table.Index;
import net.sf.jsqlparser.statement.create.table.Index.ColumnParams;
import net.sf.jsqlparser.statement.drop.Drop;
import net.sf.jsqlparser.statement.grant.Grant;

public class DDLHandler {
	private static final Logger logger = LoggerFactory.getLogger(DDLHandler.class);
	
	private static final Map<String, Class<?>> SQL_TYPES = new HashMap<>();
	
	static {
		SQL_TYPES.put("BIT",      Boolean.class);
		SQL_TYPES.put("TINYINT",  Byte.class);
		SQL_TYPES.put("SMALLINT", Integer.class);
		SQL_TYPES.put("INT",      Long.class);
		SQL_TYPES.put("FLOAT",    Float.class);
		SQL_TYPES.put("REAL",     Double.class);
		SQL_TYPES.put("DATE",     Date.class);
		SQL_TYPES.put("CHAR",     String.class);
		SQL_TYPES.put("VARCHAR",  String.class);
		SQL_TYPES.put("TEXT",     String.class);
		SQL_TYPES.put("NCHAR",    String.class);
		SQL_TYPES.put("NVARCHAR", String.class);
		SQL_TYPES.put("NTEXT",    String.class);
		SQL_TYPES.put("DOCUMENT", Document.class);
		SQL_TYPES.put("GUID",     GUID.class);
		SQL_TYPES.put("LIST",     List.class);    
	}
 
	private static Map<String, Integer> privs = Map.of("delete", User.DELETE, "select", User.SELECT, "insert", User.INSERT, "update", User.UPDATE);
	
	static void alter(Alter alter, String targetShard, String database, String user, String password) throws Exception {
		if (!SecurityHandler.getInstance().isAdmin(user, password)) {
			throw new SQLException("User " + user + " is not an admin.");
		}
		
		Table table = alter.getTable();
		String tableName = SQLUtils.getFQN(table, database);
		Metadata metadata = Metadata.getMetadata(tableName);
		
		if (metadata == null) {
			throw new DBRuntimeException("Table " + table.getName() + " does not exist.");
		}
		
		metadata.writeLock();
		
		try {
			Map<String, ColumnInfo> map = metadata.getColumns();

			for (AlterExpression exp : alter.getAlterExpressions()) {
				switch (exp.getOperation()) {
				case ADD:
					for (ColumnDataType type : exp.getColDataTypeList()) {
						String name = type.getColumnName();
						ColumnInfo colInfo = getColumnInfo(type);

						if (map.containsKey(name)) {
							throw new DBRuntimeException("The table aleady contains a column called " + name + ".");
						}

						if (!colInfo.nullable() && colInfo.defaultValue() == null) {
							throw new DBRuntimeException("You must provide a default value for " + name);
						}

						map.put(name, colInfo);
					}

					break;
				case DROP:
					String name = exp.getColumnName();

					if (!map.containsKey(name)){
						throw new DBRuntimeException("The table does not contain a column called " + exp.getColumnName() + ".");
					}

					if (metadata.getPrimaryKeyFields().contains(name)) {
						throw new DBRuntimeException(name + " is part of the primary key.");
					}

					for (IndexInfo index : metadata.getIndices()) {
						if (index.getFields().contains(name)) {
							throw new DBRuntimeException(name + " is part of an index.");
						}
					}
					map.remove(exp.getColumnName());
					break;
				default:
					break;
				}			
			}

			StorageManager.getInstance().getTableManager(tableName).setMetadata(metadata);

			metadata.store();

			if (targetShard.equals(Client.WILDCARD)) {
				SQLUtils.broadcastAll(database, alter.toString());
			}
		} catch (Exception exc) {
			throw new DBRuntimeException("The alter failed", exc);
		} finally {
			metadata.unlock();
		}
		
	}
	
	static void createIndex(CreateIndex createIndex, String targetShard, String database, String user, String password) throws Exception {
		Table table = createIndex.getTable();
		
		if (!SecurityHandler.getInstance().isAdmin(user, password)) {
			throw new SQLException("User " + user + " is not an admin.");
		}
				
		Index index = createIndex.getIndex();
		String indexName = index.getName();
		boolean unique = "unique".equalsIgnoreCase(index.getType());
		boolean fulltext = createIndex.getTailParameters().stream().anyMatch(x -> x.equalsIgnoreCase("(fulltext)"));
		List<String> fields = new ArrayList<>();
	
		File file = DBFiles.getIndex(SQLUtils.getFQN(table, database), indexName);
		
		if (file.exists()) {
			throw new DBRuntimeException("Index " + indexName + " already exists");
		}
		
		String tableName = SQLUtils.getFQN(table, database);
		Metadata metadata = Metadata.getMetadata(tableName);
		
		if (metadata == null) {
			throw new DBRuntimeException("Table " + SQLUtils.getFQN(table, database) + " does not exist.");
		}
		
		if (unique && fulltext) {
			throw new DBRuntimeException("Fulltext indices cannot be unique.");
		}
		metadata.writeLock();
		
		try {
			for (ColumnParams param : index.getColumns()) {
				String name = param.getColumnName();
				ColumnInfo info = metadata.getColumn(name);

				if (info == null) {
					throw new DBRuntimeException(name + " is not a valid comlumn");
				}

				if (fulltext && (info.type() != String.class)) {
					throw new DBRuntimeException(name + " is not a column type for fulltext indices");
				}
				
				fields.add(name);
			}
		
			if (fulltext && fields.size() != 1) {
				throw new DBRuntimeException("A full text must have a single column");
			}
			
			IndexInfo indexInfo = new IndexInfo(indexName, unique, fulltext, fields);
			List<IndexInfo> indices = metadata.getIndices();
			
			if (metadata.getIndices().contains(indexInfo)) {
				throw new DBRuntimeException("Index " + indexName + " is already defined.");
			}
			
			indexInfo.setBuilt(false);
			indices.add(indexInfo);
			metadata.store();
			
			StorageManager.getInstance().getTableManager(tableName).buildIndex(indexName);
			
			if (targetShard.equals(Client.WILDCARD)) {
				SQLUtils.broadcastAll(database, createIndex.toString());
			}
		} catch (DBRuntimeException exc) {
			throw exc;
		} catch (Exception exc) {
			logger.info(exc.getMessage());
			exc.printStackTrace();
			throw new DBRuntimeException("Create Index failed " + exc.getMessage(), exc);
		} finally {
			metadata.unlock();
		}

	}
	
	static void createTable(CreateTable createTable, String targetShard, String database, String user, String password) throws Exception {
		if (!SecurityHandler.getInstance().isAdmin(user, password)) {
			throw new SQLException("User " + user + " is not an admin.");
		}
					
		Table table = createTable.getTable();
		File file = DBFiles.getMetadata(SQLUtils.getFQN(table, database));
		
		if (file.exists()) {
			throw new DBRuntimeException("Table " + table + " already exists " + file.getAbsolutePath());
		}
		
		SortedMap<String, ColumnInfo> columns = new TreeMap<>();
		List<IndexInfo> indices = new ArrayList<>();
		List<String> primaryKeyFields = new ArrayList<>();
		
		if (createTable.getIndexes() != null) {
			for (Index index : createTable.getIndexes()) {
				String indexName = index.getName();
				List<String> fields = new ArrayList<>();

				for (ColumnParams param : index.getColumns()) {
					fields.add(param.getColumnName());
				}

				indices.add(new IndexInfo(indexName, true, false, fields));
			}
		}
		
		for (ColumnDefinition col : createTable.getColumnDefinitions()) {
			String name = col.getColumnName();
			ColumnInfo info = getColumnInfo(col);
			
			columns.put(name, info);
			
			if (info.primary()) {
				primaryKeyFields.add(name);
			}
			
		}
		
		if (primaryKeyFields.isEmpty()) {
			throw new DBRuntimeException("The table must have at least one primary key field");
		}
		
		List<String> options = createTable.getCreateOptionsStrings();
		boolean isQueue = options != null && options.stream().anyMatch(x -> x.equalsIgnoreCase("queue"));
		
		Metadata metadata = new Metadata(SQLUtils.getFQN(table, database), primaryKeyFields, columns, indices, isQueue);
		
		try {
			metadata.store();
			
			logger.info("Stored table " + SQLUtils.getFQN(table, database));
			if (targetShard.equals(Client.WILDCARD)) {
				SQLUtils.broadcastAll(database, createTable.toString());
			}
		} catch (DBRuntimeException exc) {
			throw exc;
		} catch (Exception exc) {
			exc.printStackTrace();
			throw new DBRuntimeException("Create Table failed " + exc.getMessage(), exc);
		} 
		
	}
	
	static void createUser(String sql, String targetShard, String database, String user, String password, String newUser, String newPassword) throws Exception {
		if (!SecurityHandler.getInstance().isAdmin(user, password)) {
			throw new SQLException("User " + user + " is not an admin.");
		}	
		
		SecurityHandler.getInstance().createUser(newUser, newUser, newPassword, false);

		if (targetShard.equals(Client.WILDCARD)) {
			SQLUtils.broadcastAll(database, sql);
		}
		
	}
	
	static void drop(Drop drop, String targetShard, String database, String user, String password) throws Exception {	
		if (!SecurityHandler.getInstance().isAdmin(user, password)) {
			throw new SQLException("User " + user + " is not an admin.");
		}
				
		String type = drop.getType();
		List<String> parameters = drop.getParameters();
		String table = type.equalsIgnoreCase("INDEX") ? SQLUtils.getFQN(parameters.get(1), database) : SQLUtils.getFQN(drop.getName(), database);
		Metadata metadata = Metadata.getMetadata(table);
		
		if (metadata == null) {
			if (drop.isIfExists()) {
				return;
			} else {
				throw new DBRuntimeException("Table " + table  + " does not exist.");
			}
		}
		
		metadata.writeLock();
		
		try {
			if (type.equalsIgnoreCase("INDEX")) {
				String index = drop.getName().getName();
				
				List<IndexInfo> indices = metadata.getIndices();

				for (int idx = 0; idx < indices.size(); idx++) {
					if (indices.get(idx).getName().equals(index)) {
						indices.remove(idx);
						break;
					}
				}

				metadata.store();

				if (targetShard.equals(Client.WILDCARD)) {
					SQLUtils.broadcastAll(database, drop.toString());
				}
			} else if (type.equalsIgnoreCase("TABLE")) {
				File tableDir = DBFiles.getTableDirectory(table);
				StorageManager.getInstance().closeTable(table);
				FileUtils.deleteDirectory(tableDir);
				metadata.removeFromCache();

				if (targetShard.equals(Client.WILDCARD)) {
					SQLUtils.broadcastAll(database, drop.toString());
				}
			} else {
				throw new UnsupportedSyntaxException("Drop " + type + " is not supported.");
			} 
		} finally {
			metadata.unlock();
		}

	}
	
	
	static void grant(Grant grant, String targetShard, String database, String user, String password) throws Exception {
		if (!SecurityHandler.getInstance().isAdmin(user, password)) {
			throw new SQLException("User " + user + " is not an admin.");
		}
		

		String table = SQLUtils.getFQN(grant.getObjectName(), database);
		int grants = 0;
		
		for (String priv : grant.getPrivileges()) {
			Integer value = privs.get(priv.toLowerCase());
			
			if (value == null) {
				throw new Exception(priv + " is not a valid privilege");
			}
			
			grants = User.add(grants, value);
		}
		
		for (String grantee : grant.getUsers()) {
			logger.info("grant on " + table);
			SecurityHandler.getInstance().grant(grantee, table, grants);
		}

		if (targetShard.equals(Client.WILDCARD)) {
			SQLUtils.broadcastAll(database, grant.toString());
		}
	}
	
	private static ColumnInfo getColumnInfo(ColumnDefinition col) throws Exception {
		Class<?> type =  sqlTypeToClass(col.getColDataType().getDataType());
		
		if (type == null) {
			throw new DBRuntimeException("The type for column " + col.getColumnName() + " is not valid");
		}
		
		List<String> specs = col.getColumnSpecs();
		boolean nullable = true;
		boolean primary = false;
		Object defaultValue = null;
		
		if (specs != null) {
			for (int idx = 0; idx < specs.size(); idx++) {
				String spec = specs.get(idx);
				String nextSpec = idx == specs.size() - 1 ? "" : specs.get(idx + 1);
				
				if (spec.equalsIgnoreCase("PRIMARY")) {
					primary = true;
				} else if (spec.equalsIgnoreCase("NOT") && nextSpec.equalsIgnoreCase("NULL")) {
					nullable = false;
				} else if (spec.equalsIgnoreCase("DEFAULT") && nextSpec.length() != 0) {
					if (nextSpec.startsWith("'")) {
						defaultValue = nextSpec.substring(1, nextSpec.length() - 1);
					} else {
						defaultValue = Converter.convert(nextSpec, type);
					}
				}
			}
		}
		
		return new ColumnInfo(type, primary, nullable, defaultValue);
	}
	
	
	
	private static Class<?> sqlTypeToClass(String sqlClass) {
		int idx = sqlClass.indexOf('(');
		
		if (idx != -1) {
			sqlClass = sqlClass.substring(0, idx);
		}
		
		return SQL_TYPES.get(sqlClass.toUpperCase());
	
	}
	
}
