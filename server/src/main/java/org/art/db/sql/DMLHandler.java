package org.art.db.sql;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.art.db.client.Client;
import org.art.db.common.Converter;
import org.art.db.common.DBNull;
import org.art.db.common.DBRuntimeException;
import org.art.db.common.DiscoveryHandler;
import org.art.db.common.Document;
import org.art.db.common.DocumentSet;
import org.art.db.query.DocumentComparer;
import org.art.db.query.OrderBy;
import org.art.db.query.QueryManager;
import org.art.db.security.SecurityHandler;
import org.art.db.security.User;
import org.art.db.storage.ColumnInfo;
import org.art.db.storage.Metadata;
import org.art.db.storage.Metrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.sf.jsqlparser.expression.Alias;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.operators.relational.ExpressionList;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.select.AllColumns;
import net.sf.jsqlparser.statement.select.Limit;
import net.sf.jsqlparser.statement.select.Offset;
import net.sf.jsqlparser.statement.select.OrderByElement;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.statement.select.Values;
import net.sf.jsqlparser.statement.update.Update;
import net.sf.jsqlparser.statement.update.UpdateSet;

public class DMLHandler {
	
	@SuppressWarnings("unused")
	private static final Logger logger = LoggerFactory.getLogger(DMLHandler.class);
	
	private static List<OrderBy> buildDefaultOrderBy(Metadata metadata) {
		List<OrderBy> orderByList = new ArrayList<>();
		

		for (String key : metadata.primaryKeyFields()) {
			orderByList.add(new OrderBy(key, true));
		}

		return orderByList;
	}

	private static List<OrderBy> buildOrderBy(List<OrderByElement> elements) {
		List<OrderBy> orderByList = new ArrayList<>();

		if (elements != null) {
			for (OrderByElement element : elements) {
				if (element.getExpression() instanceof Column col) {
					orderByList.add(new OrderBy(col.getColumnName(), element.isAsc()));
				}
			}
		}

		return orderByList;
	}
	
	private static List<String> buildSelectList(List<SelectItem<?>> selectItems) {
		List<String> result = new ArrayList<>();
		
		if (selectItems != null) {
			for (SelectItem<?> item : selectItems) {
				if (item.getExpression() instanceof Column col) {
					result.add(col.getColumnName());
				} else  {
					result.clear();
					break;
				} 
			}
		}
		
		return result;
	}
	
	
	static void delete(Delete delete, String sqlString, String targetShard, String database, String user, String password, List<Comparable<?>> parameters) throws Exception {
		
		String table = SQLUtils.getFQN(delete.getTable(), database);
		
		if (!SecurityHandler.getInstance().isInRole(user, password, table, User.DELETE)) {
			throw new SQLException("User " + user + " does not have delete access to " + table);
		}
		
		Expression where = delete.getWhere();
		
		String shard = SQLUtils.getShard(table, where, parameters);
		
		if (!SQLUtils.isShardLocal(shard)) {
			SQLUtils.forwardExecute(database, shard, sqlString, user, password, parameters);
			return;
		}
		
		if (shard.equals(Client.WILDCARD) && targetShard.equals(Client.WILDCARD)) {
			SQLUtils.broadcastExecute(database, sqlString, user, password, parameters);
		}
		
		deleteLocal(delete, sqlString, database, user, password, parameters);
		
	}
	
	private static void deleteLocal(Delete delete, String sqlString, String database, String user, String password, List<Comparable<?>> parameters) {
		try {
			String table = SQLUtils.getFQN(delete.getTable(), database);
			Expression where = delete.getWhere();
			long start = System.currentTimeMillis();
			
			if (where == null) {
				throw new DBRuntimeException("The delete must contain a where clause");
			}

			ExpressionEvaluator evaluator = new ExpressionEvaluator(table);
			Expression normalized = new ExpressionNormalizer().normalize(where);
			
			DocumentSet docs = evaluator.evaluateWhere(normalized, null, null, parameters);

			for (Document doc : docs) {
				QueryManager.getInstance().remove(table, doc);
			}
			
			Metrics.getInstance().logDelete();
			Metrics.getInstance().logSelect(sqlString, System.currentTimeMillis() - start, 1);
			
		} catch (DBRuntimeException exc) {
			throw exc;
		} catch (Exception exc) {
			throw new DBRuntimeException("The delete failed", exc);
		}
				
	}
	
	static void insert(Insert insert, String sqlString, String targetShard, String database, String user, String password, List<Comparable<?>> parameters) {
		try {
			String table = SQLUtils.getFQN(insert.getTable(), database);
			Metadata metadata = Metadata.getMetadata(table);
			long start = System.currentTimeMillis();
			
			if (metadata == null) {
				throw new DBRuntimeException("Table " + insert.getTable().getName() + " does not exist");
			}
			
			if (!SecurityHandler.getInstance().isInRole(user, password, table, User.INSERT)) {
				throw new SQLException("User " + user + " does not have insert access to " + table);
			}
			
			Values values = (Values)insert.getSelect();
			ExpressionList<Column> columns = insert.getColumns();
			ExpressionList<?> expressions  = values.getExpressions();
			ExpressionEvaluator evaluator = new ExpressionEvaluator(table);
						
			if (columns.size() != expressions.size()) {
				throw new DBRuntimeException("The column list and the values list do not match");
			}
			
			Document doc = new Document();
			
			for (int idx = 0; idx < insert.getColumns().size(); idx++) {
				String column = columns.get(idx).getColumnName();
				String prefix = column.contains(".") ? column.substring(0, column.indexOf('.')) : column;
				ColumnInfo info = metadata.column(prefix);
				
				if (info == null) {
					throw new DBRuntimeException(column + " is not a valid column name");
				}
				
				Object value = evaluator.evaluate(expressions.get(idx), null, parameters);
				
				if (value == DBNull.NULL && !info.nullable()) {
					throw new DBRuntimeException("The value for " + column + " cannot be null");
				}
				
				doc.put(column, Converter.convert(value, info.type()));
			}
			
			for (Map.Entry<String, ColumnInfo> entry : metadata.columns().entrySet()) {
				String name = entry.getKey();
				ColumnInfo info = entry.getValue();
				
				if (!doc.containsKey(name)) {
					Object dflt = info.defaultValue();
					
					if (dflt == null && !info.nullable()) {
						throw new DBRuntimeException("The insert must supply a value for " + name);
					} else if (dflt != null) {
						doc.put(name, Converter.convert(dflt, info.type()));
					}
				}
			}
			
			String shard = SQLUtils.getShard(table, doc);
			
			if (!SQLUtils.isShardLocal(shard)) {
				SQLUtils.forwardExecute(database, shard, sqlString, user, password, parameters);
				return;
			}
			
			QueryManager.getInstance().insert(table, doc);	
			
			Metrics.getInstance().logInsert();
			Metrics.getInstance().logSelect(sqlString, System.currentTimeMillis() - start, 1);
		} catch (DBRuntimeException exc) {
			throw exc;
		} catch (Exception exc) {
			exc.printStackTrace();
			throw new DBRuntimeException("The insert failed", exc);
		}
		
	}
	
	static DocumentSet select(Select select, String sqlString, String targetShard, String database, String user, String password, List<Comparable<?>> parameters) throws Exception {
		if (select instanceof PlainSelect plainSelect && plainSelect.getFromItem() instanceof Table fromTable) {
			String table = SQLUtils.getFQN(fromTable, database);
			
			if (!SecurityHandler.getInstance().isInRole(user, password, table, User.SELECT)) {
				throw new SQLException("User " + user + " does not have select access to " + table);
			}
			
			Expression where = plainSelect.getWhere();
			String shard = SQLUtils.getShard(table, where, parameters);
			
			if (!SQLUtils.isShardLocal(shard)) {
				return SQLUtils.forwardExecuteQuery(database, shard, sqlString, user, password, parameters);
			}
			
			DocumentSet set = selectLocal(plainSelect, sqlString, fromTable, database, user, password, parameters);

			// Merge the document sets from the other shards, using the order by clause
			// or the primary key as the sort key.
			
			if (shard.equals(Client.WILDCARD) && targetShard.equals(Client.WILDCARD)) {
				List<OrderBy> orderByList = buildOrderBy(plainSelect.getOrderByElements());
				
				if (orderByList.isEmpty()) {
					orderByList = buildDefaultOrderBy(Metadata.getMetadata(table));
				}
				
				DocumentComparer comparer = new DocumentComparer(orderByList);
				
				for (DocumentSet other : SQLUtils.broadcastExecuteQuery(database, sqlString, user, password, parameters)) {
					set = set.merge(other, comparer);
				}
				
			}
			
			// Handle the offset and limit clauses
			
			ExpressionEvaluator evaluator = new ExpressionEvaluator(table);
			Limit limitClause = plainSelect.getLimit();
			long limit = limitClause == null || limitClause.getRowCount() == null ? Long.MAX_VALUE : (long)evaluator.evaluate(limitClause.getRowCount(), Long.class);
			
			Offset offsetClause = plainSelect.getOffset();
			long offset = offsetClause == null  || offsetClause.getOffset() == null ? 0 : (long)evaluator.evaluate(offsetClause.getOffset(), Long.class);
			
			
			if (offset != 0 || limit != Long.MAX_VALUE) {
				try (DocumentSet newSet = new DocumentSet()) {
					long index = 0;

					for (Document doc : set) {
						if (newSet.size() == limit) { 
							break;
						} else if (index >= offset) {
							newSet.add(doc);
						}

						index++;
					}

					set = newSet;
				}
			}
			
			return set;
		}
		
		throw new UnsupportedOperationException("Syntax not supported.");
	}
	
	private static DocumentSet selectLocal(PlainSelect plainSelect, String sqlString, Table fromTable, String database, String user, String password, List<Comparable<?>> parameters) throws Exception {
		
		String table = SQLUtils.getFQN(fromTable, database);
		long start = System.currentTimeMillis();
		
		if (Metadata.getMetadata(table) == null) {
			throw new DBRuntimeException("Table " + fromTable.getName() + " does not exist");
		}
		
		// Normalize the where clause
		
		Expression where = plainSelect.getWhere();
		
		if (where == null) {
			throw new DBRuntimeException("The select must contain a where clause");
		}

		Expression normalized = new ExpressionNormalizer().normalize(where);
		
		
		// Find the order by list and the selected columns
		
		List<OrderBy> orderByList = buildOrderBy(plainSelect.getOrderByElements());
		List<String> selectColumns = buildSelectList(plainSelect.getSelectItems());
						
		// Get the document set then process any aliases and expressions in the select items
		
		ExpressionEvaluator evaluator = new ExpressionEvaluator(table);
		DocumentSet set = evaluator.evaluateWhere(normalized, selectColumns, orderByList, parameters);	
				
		if (!plainSelect.getSelectItems().stream().anyMatch(x -> x.getExpression() instanceof AllColumns)) {		
			for (Document doc : set) {
				for (SelectItem<?> item : plainSelect.getSelectItems()) {
					Alias alias = item.getAlias();
					Expression exp = item.getExpression();
					
					if (exp instanceof Column col && alias != null) {
						doc.put(alias.getName(), doc.get(col.getColumnName()));
					} else if (exp instanceof Column) {
						continue;
					} else if (alias == null) {
						throw new DBRuntimeException("Missing alias for expression for expression " + exp);
					} else {						
						String name = alias.getName();
						Object value = evaluator.evaluate(exp, doc, parameters);
						
						doc.put(name, value);
					}
				}	
			}
		}
		
		Metrics.getInstance().logSelect(sqlString, System.currentTimeMillis() - start, set.size());
		
		return set;
		
	}

	static void update(Update update, String sqlString, String targetShard, String database, String user, String password, List<Comparable<?>> parameters) throws Exception {
		String table = SQLUtils.getFQN(update.getTable(), database);
		
		if (!SecurityHandler.getInstance().isInRole(user, password, table, User.UPDATE)) {
			throw new SQLException("User " + user + " does not have update access to " + table);
		}
		
		Expression where = update.getWhere();
		
		if (where == null) {
			throw new DBRuntimeException("The update must contain a where clause");
		}

		String shard = SQLUtils.getShard(table, where, parameters);
		
		if (!SQLUtils.isShardLocal(shard)) {
			SQLUtils.forwardExecute(database, shard, sqlString, user, password, parameters);
			return;
		}
		
		if (shard.equals(Client.WILDCARD) && targetShard.equals(Client.WILDCARD)) {
			SQLUtils.broadcastExecute(database, sqlString, user, password, parameters);
		}
		
		updateLocal(update, sqlString, database, user, password, parameters);
		
	}
	
	static void updateLocal(Update update, String sqlString, String database, String user, String password, List<Comparable<?>> parameters) {
		try {
			String table = SQLUtils.getFQN(update.getTable(), database);
			Metadata metadata = Metadata.getMetadata(table);
			long start = System.currentTimeMillis();
			
			if (metadata == null) {
				throw new DBRuntimeException("Table " + update.getTable().getName() + " does not exist");
			}
			
			for (UpdateSet set : update.getUpdateSets()) {
				List<Column> columns = set.getColumns();
				
				for (int idx = 0; idx < columns.size(); idx++) {
					String column = columns.get(idx).getFullyQualifiedName();
					String prefix = column.contains(".") ? column.substring(0, column.indexOf('.')) : column;					
					
					if ( metadata.column(prefix) == null) {
						throw new DBRuntimeException(column + " is not a valid column");
					}
				}
			}
			
			Expression where = update.getWhere();
			
			ExpressionEvaluator evaluator = new ExpressionEvaluator(table);
			Expression normalized = new ExpressionNormalizer().normalize(where);
			DocumentSet docs =  evaluator.evaluateWhere(normalized, null, null, parameters);
			
			for (Document oldDoc : docs) {
				Document newDoc = new Document(oldDoc);
				
				for (UpdateSet set : update.getUpdateSets()) {
					List<Column> columns = set.getColumns();
					ExpressionList<?> values = set.getValues();

					for (int idx = 0; idx < columns.size(); idx++) {
						String column = columns.get(idx).getFullyQualifiedName();
						int dot = column.indexOf('.');
						ColumnInfo info = dot == -1 ? metadata.column(column) : metadata.column(column.substring(0, dot));
						Object value = evaluator.evaluate(values.get(idx), oldDoc, parameters);
						
						if (value == DBNull.NULL && !info.nullable()) {
							throw new DBRuntimeException("The value for " + column + " cannot be null");
						}
						
						if (info.type() == Document.class) {
							newDoc.put(column, value);
						} else {
							newDoc.put(column, Converter.convert(value,  info.type()));
						}
					}
				}
				
				QueryManager.getInstance().update(table, oldDoc, newDoc);
			}
			
			Metrics.getInstance().logUpdate();
			Metrics.getInstance().logSelect(sqlString, System.currentTimeMillis() - start, 1);
		} catch (DBRuntimeException exc) {
			throw exc;
		} catch (Exception exc) {
			exc.printStackTrace();
			throw new DBRuntimeException("The update failed", exc);
		}
	}
	
}
