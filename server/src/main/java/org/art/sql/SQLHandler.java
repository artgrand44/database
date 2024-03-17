package org.art.sql;

import java.util.List;

import org.art.storage.DBRuntimeException;
import org.art.db.common.ServerDocumentSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.alter.Alter;
import net.sf.jsqlparser.statement.create.index.CreateIndex;
import net.sf.jsqlparser.statement.create.table.CreateTable;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.drop.Drop;
import net.sf.jsqlparser.statement.grant.Grant;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.update.Update;

public class SQLHandler {
	
	@SuppressWarnings("unused")
	private static final Logger logger = LoggerFactory.getLogger(SQLHandler.class);
	
	private static SQLHandler instance = new SQLHandler();
	
	private final Cache<String, Statement> cache = Caffeine.newBuilder().maximumSize(1000).build();
	
	public void execute(String shard, String database, String user, String password, String sql, List<Comparable<?>> parameters) throws Exception {
			
		try {
			String[] tokens = sql.split(" ");
			
			if (tokens.length == 5
				&& (tokens[0].equalsIgnoreCase("create") || tokens[0].equalsIgnoreCase("alter"))
				&& tokens[1].equalsIgnoreCase("user")
				&& tokens[3].equalsIgnoreCase("password") ){
				DDLHandler.createUser(sql, shard, database, user, password, tokens[2], tokens[4]);
				return;
			}
			
			Statement statement = this.parse(sql);
			
			if (statement instanceof Alter alter) {
				DDLHandler.alter(alter, shard, database, user, password);
			} else if (statement instanceof CreateTable create) {
				DDLHandler.createTable(create, shard, database, user, password);
			} else if (statement instanceof CreateIndex create) {
				DDLHandler.createIndex(create, shard, database, user, password);
			} else if (statement instanceof Delete delete) {
				DMLHandler.delete(delete, shard, database, user, password, parameters);
			} else if (statement instanceof Drop drop) {
				DDLHandler.drop(drop, shard, database, user, password);
			} else if (statement instanceof Grant grant) {
				DDLHandler.grant(grant, shard, database, user, password);
			} else if (statement instanceof Insert insert) {
				DMLHandler.insert(insert, shard, database, user, password, parameters);
			} else if (statement instanceof Update update) {
				DMLHandler.update(update, shard, database, user, password, parameters);
			} else if (statement instanceof Select) {
				throw new DBRuntimeException("Select statements must be called via Client.executeQuery.");
			}
		} catch (JSQLParserException exc) {
			String[] lines = exc.getMessage().split("\\n");
			String message = "The statement was invalid\n" + sql + "\n" + lines[0] + "\n" + lines[1];
			
			throw new DBRuntimeException(message);			
		} 
	}
	
	public ServerDocumentSet executeQuery(String shard, String database,  String user, String password, String sql, List<Comparable<?>> parameters) throws Exception {
		try {
			Statement statement = this.parse(sql);
		
			if (statement instanceof Select select) {
				return DMLHandler.select(select, shard, database, user, password, parameters);
			} else {
				throw new DBRuntimeException("Client.executeQuery can only be used for select statements.");
			}
		} catch (JSQLParserException exc) {
			String[] lines = exc.getMessage().split("\\n");
			String message = "The statement was invalid\n" + sql + "\n" + lines[0];
			
			if (lines.length > 1) {
				message += "\n" + lines[1];
			}
			
			throw new DBRuntimeException(message);			
		} 
	}
	
	private Statement parse(String sql) throws JSQLParserException {
		Statement statement = cache.getIfPresent(sql);
		
		if (statement == null) {
			statement  = CCJSqlParserUtil.parse(sql);
			cache.put(sql, statement);
		}
		
		return statement;
	}
	
	public static SQLHandler getInstance() {
		return instance;
	}
	
}
