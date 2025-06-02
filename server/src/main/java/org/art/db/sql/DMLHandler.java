package org.art.db.sql;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.art.db.client.Client;
import org.art.db.common.Comparison;
import org.art.db.common.Converter;
import org.art.db.common.DBNull;
import org.art.db.common.DBRuntimeException;
import org.art.db.common.Document;
import org.art.db.common.DocumentSet;
import org.art.db.common.Key;
import org.art.db.common.OrderBy;
import org.art.db.query.QueryManager;
import org.art.db.security.SecurityHandler;
import org.art.db.security.User;
import org.art.db.storage.BackupHandler;
import org.art.db.storage.ColumnInfo;
import org.art.db.storage.Metadata;
import org.art.db.storage.MetricsLogger;
import org.art.db.storage.StorageManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.sf.jsqlparser.expression.Alias;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.expression.operators.relational.ExpressionList;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.ReturningClause;
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
	
	private static Set<String> queueFuncs = Set.of("lease", "leaseByKey", "renewLease", "isLeased", "isLeasedBy", "isUnleased");
	
	private static Document buildGroup(List<SelectItem<?>> items, List<Accumulator<?>> accumulators) {
		Document doc = new Document();

		for (int idx = 0; idx < accumulators.size(); idx++) {
			SelectItem<?> item = items.get(idx);
			Alias alias = item.getAlias();
			Accumulator<?> accumulator = accumulators.get(idx);
			String name;

			if (alias == null && item.getExpression() instanceof Column col) {
				name = col.getColumnName();
			} else if (alias == null) {
				throw new DBRuntimeException(item.getExpression() + " requires an alias");
			} else {
				name = alias.getName();
			}

			doc.put(name, accumulator.compute());
		}
		
		return doc;
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
	
	private static int compare(List<OrderBy> orderByList, Document first, Document second) {
		
		for (OrderBy orderBy : orderByList) {
			String name = orderBy.field();

			Object firstField = first.get(name);
			Object secondField = second.get(name);

			if (firstField == null || secondField == null) {
				continue;
			}
			
			if (firstField instanceof DBNull && ! (secondField instanceof DBNull)) {
				return -1;
			}
			
			if (secondField instanceof DBNull && ! (firstField instanceof DBNull)) {
				return +1;
			}
			
			int comp = Comparison.compare(firstField, secondField);

			if (comp != 0) {
				return orderBy.ascending() ? comp : - comp;
			}
		}
		
		return 0;
	}

	static DocumentSet delete(Delete delete, String sqlString, String targetShard, String database, String user, String password, List<Comparable<?>> parameters) throws Exception {
		
		String table = SQLUtils.getFQN(delete.getTable(), database);
		
		if (!SecurityHandler.getInstance().isInRole(user, password, table, User.DELETE)) {
			throw new SQLException("User " + user + " does not have delete access to " + table);
		}
		
		Expression where = delete.getWhere();
		
		String shard = SQLUtils.getShard(table, where, parameters);
		
		if (!SQLUtils.isShardLocal(shard)) {
			return SQLUtils.forwardExecuteQuery(database, shard, sqlString, user, password, parameters);
		}
		
		DocumentSet set = deleteLocal(delete, sqlString, database, user, password, parameters);
		
		if (shard.equals(Client.WILDCARD) && targetShard.equals(Client.WILDCARD)) {
			Limit limitClause = delete.getLimit();
			long limit = getLimit(limitClause, table);

			for (DocumentSet other : SQLUtils.broadcastExecuteQuery(database, delete, limitClause,  limit - set.size(), user, password, parameters)) {
				set.addAll(other);
			}
		}
		
		return delete.getReturningClause() == null ? new DocumentSet() : set;
		
	}
	
	private static DocumentSet deleteLocal(Delete delete, String sqlString, String database, String user, String password, List<Comparable<?>> parameters) {
		long start = System.currentTimeMillis();
		
		String table = SQLUtils.getFQN(delete.getTable(), database);
		DocumentSet docs = null;
		boolean lock = false;
		Metadata metadata = Metadata.getMetadata(table);
		
		if (metadata == null) {
			throw new DBRuntimeException("Table " + table + " does not exist.");
		}
		
		metadata.readLock();
		
		BackupHandler.getInstance().startUpdate();
		
		try {
			Expression where = delete.getWhere();
			ReturningClause returnClause = delete.getReturningClause();
									
			if (where == null) {
				throw new DBRuntimeException("The delete must contain a where clause");
			}
			
			if (metadata.isQueue() && !isValidQueueQuery(where, metadata, false)) {
				throw new DBRuntimeException("The where clause is not supported for queries.");
			}
			
			// Find the documents and delete them

			ExpressionEvaluator evaluator = new ExpressionEvaluator(table);
			Expression normalized = new ExpressionNormalizer().normalize(where);
			long limit = getLimit(delete.getLimit(), table);
			
			lock = !isPrimaySearch(metadata, normalized);
			docs = trim(evaluator.evaluateWhere(normalized, null, parameters, limit, lock), delete.getLimit(), evaluator);

			for (Document doc : docs) {
				QueryManager.getInstance().remove(table, doc);
			}
			
			return returnClause == null || returnClause.isEmpty() 
				   ? new DocumentSet()
				   : processSelectItems(docs, returnClause, evaluator, parameters);
				
		} catch (DBRuntimeException exc) {
			throw exc;
		} catch (Exception exc) {
			throw new DBRuntimeException("The delete failed" + exc.getMessage(), exc);
		} finally {
			if (lock && docs != null) {
				QueryManager.getInstance().unlockPrimaryAllKeys(table, docs);
			}
			
			metadata.unlock();
			BackupHandler.getInstance().endUpdate();
			MetricsLogger.getInstance().logDelete();
			MetricsLogger.getInstance().logSelect(sqlString, System.currentTimeMillis() - start, 1);
		}
				
	}
	
	static long getLimit(Limit limitClause, String table) throws Exception {
		if (limitClause == null || limitClause.getRowCount() == null) {
			return Long.MAX_VALUE;
		}
		
		ExpressionEvaluator evaluator = new ExpressionEvaluator(table);
		
		return (long)evaluator.evaluate(limitClause.getRowCount(), Long.class);
	}
	
	private static boolean isPrimaySearch(Metadata metadata, Expression where) {
		if (where instanceof FilterExpression exp) {
			return exp.getFilters().stream().allMatch(x -> metadata.getPrimaryKeyFields().contains(x.getField()));
		}
		
		return false;
	}
	
	private static boolean isValidQueueQuery(Expression where, Metadata metadata, boolean query) {
		List<String> primaryKeys = metadata.getPrimaryKeyFields();
		
		
		if (where instanceof EqualsTo eq) {
			if (primaryKeys.size() == 1 && eq.getLeftExpression() instanceof Column col && col.getColumnName().equals(primaryKeys.get(0))) {
				return true;
			}
			
			if (primaryKeys.size() == 1 && eq.getRightExpression() instanceof Column col && col.getColumnName().equals(primaryKeys.get(0))) {
				return true;
			}
		}
		
		 return query && where instanceof Function fun && queueFuncs.contains(fun.getName());
	}
	
	
	static void insert(Insert insert, String sqlString, String targetShard, String database, String user, String password, List<Comparable<?>> parameters) {
		
		String table = SQLUtils.getFQN(insert.getTable(), database);
		Metadata metadata = Metadata.getMetadata(table);
		
		if (metadata == null) {
			metadata = Metadata.getMetadata(table);
			throw new DBRuntimeException("Table " + insert.getTable().getName() + " does not exist.");
		}
		
		metadata.readLock();
		
		try {
			BackupHandler.getInstance().startUpdate();
			
			long start = System.currentTimeMillis();
			
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
				ColumnInfo info = metadata.getColumn(prefix);
				
				if (info == null) {
					throw new DBRuntimeException(column + " is not a valid column name");
				}
				
				Object value = evaluator.evaluate(expressions.get(idx), null, parameters);
				
				if (value == DBNull.NULL && !info.nullable()) {
					throw new DBRuntimeException("The value for " + column + " cannot be null");
				}
				
				doc.put(column, Converter.convert(value, info.type()));
			}
			
			for (Map.Entry<String, ColumnInfo> entry : metadata.getColumns().entrySet()) {
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
			QueryManager.getInstance().unlockPrimaryKey(table, doc);
			
			MetricsLogger.getInstance().logInsert();
			MetricsLogger.getInstance().logSelect(sqlString, System.currentTimeMillis() - start, 1);
		} catch (DBRuntimeException exc) {
			throw exc;
		} catch (Exception exc) {
			exc.printStackTrace();
			throw new DBRuntimeException("The insert failed " + exc.getMessage(), exc);
		} finally {
			metadata.unlock();
			BackupHandler.getInstance().endUpdate();
		}
		
	}
	
	private static DocumentSet processSelectItems(DocumentSet set, List<SelectItem<?>> items, ExpressionEvaluator evaluator, List<Comparable<?>> parameters) {
		if (!items.stream().anyMatch(x -> x.getExpression() instanceof AllColumns)) {		
			for (Document doc : set) {
				for (SelectItem<?> item : items) {
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
		
		return set;
	}
	
	
	private static DocumentSet processGroupBy(DocumentSet set, PlainSelect plainSelect, Metadata metadata, ExpressionEvaluator evaluator, List<Comparable<?>> parameters) throws Exception {
		
		if (set.isEmpty()) {
			return set;
		}
		
		// Unpack the pieces of the select statement
		
		ExpressionList<?> expressions = plainSelect.getGroupBy().getGroupByExpressionList();
		Expression having = plainSelect.getHaving();
		List<SelectItem<?>> items = plainSelect.getSelectItems();
		
		// Sort the documents by the group by fields.
		
		set.sort((x, y) -> getGroupByKey(x, expressions, evaluator, parameters).compareTo(getGroupByKey(y, expressions, evaluator, parameters)));
		
		// Build a set of accumulators to accumulate the column values for each group
		
		List<Accumulator<?>> accumulators = new ArrayList<>();
		
		for (SelectItem<?> item : items) {
			accumulators.add(Accumulators.getAccumulator(evaluator, item.getExpression(), parameters, metadata));
		}
		
		Key prevKey = null;
		DocumentSet result = new DocumentSet();
		
		// Iterate over the documents and update the accumulators.  When you get to the
		// end of a group, build a document representing the group and clear the accumulators.
		
		for (Document doc : set) {
			Key groupKey = getGroupByKey(doc, expressions, evaluator, parameters);
			
			if (prevKey != null && !prevKey.equals(groupKey)) {
				Document groupDoc = buildGroup(items, accumulators);
				
				if (having == null || evaluator.evaluate(having, groupDoc, parameters, Boolean.class)) {
					result.add(groupDoc);
				} 
				
				accumulators.forEach(x -> x.clear());
			}
				
			prevKey = groupKey;
			
			for (Accumulator<?> accumulator : accumulators) {
				accumulator.process(doc);
			}
		}
		
		// Add a document for the last group.
		
		Document groupDoc = buildGroup(items, accumulators);
		
		if (having == null || evaluator.evaluate(having, groupDoc, parameters, Boolean.class)) {
			result.add(groupDoc);
		}
		
		return result;
		
	}
	
		
	@SuppressWarnings("resource")
	static DocumentSet select(Select select, String sqlString, String targetShard, String database, String user, String password, List<Comparable<?>> parameters) throws Exception {
		if (select instanceof PlainSelect plainSelect && plainSelect.getFromItem() instanceof Table fromTable) {
			String table = SQLUtils.getFQN(fromTable, database);
			
			if (!SecurityHandler.getInstance().isInRole(user, password, table, User.SELECT)) {
				throw new SQLException("User " + user + " does not have select access to " + table);
			}
			
			Expression where = plainSelect.getWhere();
			String shardContainingData = SQLUtils.getShard(table, where, parameters);
			
			if (!SQLUtils.isShardLocal(shardContainingData)) {
				return SQLUtils.forwardExecuteQuery(database, shardContainingData, sqlString, user, password, parameters);
			}
						
			// See if this is a select count(*) with no where clause.  If so, return the result.
			
			DocumentSet set = selectCount(plainSelect, sqlString, targetShard, database, user, password, parameters);
						
			if (set != null) {
				return set;
			}
			
			// Go on to the normal case.
			
			set = selectLocal(plainSelect, sqlString, fromTable, database, user, password, parameters);
			ExpressionEvaluator evaluator = new ExpressionEvaluator(table);
			
			// If the select is coming from an application and the data is on multiple shards
			// then get the document sets from the other shards and merge them together.
			
			if (shardContainingData.equals(Client.WILDCARD) && targetShard.equals(Client.WILDCARD)) {
				List<DocumentSet> others = SQLUtils.broadcastExecuteQuery(database, sqlString, user, password, parameters);
				
				for (DocumentSet other : others) {
					set.addAll(other);
				}
			} 
			
			// Process the group by
						
			if (plainSelect.getGroupBy() != null) {
				set = processGroupBy(set, plainSelect, Metadata.getMetadata(table), evaluator, parameters) ;
			}
			
			// Process the order by
			
			List<OrderBy> orderBy = buildOrderBy(plainSelect.getOrderByElements());
			
			if (orderBy != null && !orderBy.isEmpty()) {
				set.sort((x, y) -> compare(orderBy, x, y));
			}
			
			// Enforce the offset and limit clauses.
						
			Limit limitClause = plainSelect.getLimit();
			long limit = limitClause == null || limitClause.getRowCount() == null ? Long.MAX_VALUE : (long)evaluator.evaluate(limitClause.getRowCount(), Long.class);

			Offset offsetClause = plainSelect.getOffset();
			long offset = offsetClause == null  || offsetClause.getOffset() == null ? 0 : (long)evaluator.evaluate(offsetClause.getOffset(), Long.class);

			if ((offset != 0 || limit != Long.MAX_VALUE) && targetShard.equals(Client.WILDCARD)) {
				DocumentSet newSet = new DocumentSet();
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
			
			return set;
		}
		
		throw new UnsupportedOperationException("Syntax not supported.");
	}
	
	private static DocumentSet selectCount(PlainSelect select, String sqlString, String targetShard, String database, String user, String password, List<Comparable<?>> parameters) throws Exception {

		Table fromTable = (Table) select.getFromItem();
		String table = SQLUtils.getFQN(fromTable, database);
		Expression where = select.getWhere();

		if (where == null 
			&& select.getSelectItems() != null 
			&& select.getSelectItems().size() == 1
			&& select.getSelectItems().get(0).getExpression() instanceof Function func
			&& func.getName().equalsIgnoreCase("count")
			&& func.getParameters().size() == 1
			&& func.getParameters().get(0) instanceof AllColumns) {
			
			long count = StorageManager.getInstance().getTableManager(table).getDocumentCount();
			String shardContainingData = SQLUtils.getShard(table, where, parameters);

			if (shardContainingData.equals(Client.WILDCARD) && targetShard.equals(Client.WILDCARD)) {
				List<DocumentSet> others = SQLUtils.broadcastExecuteQuery(database, sqlString, user, password, parameters);

				for (DocumentSet other : others) {
					Document doc = other.iterator().next();
					count += doc.get("count", Long.class);
				}
			} 

			DocumentSet set = new DocumentSet();
			Document doc = new Document();
			SelectItem<?> item = select.getSelectItems().get(0);
			Alias alias = item.getAlias();
			String column = alias == null ? "count" : alias.getName();

			doc.put(column, count);
			set.add(doc);

			return set;
		}

		return null;

	}
	
	private static DocumentSet selectLocal(PlainSelect plainSelect, String sqlString, Table fromTable, String database, String user, String password, List<Comparable<?>> parameters) throws Exception {
		
		String table = SQLUtils.getFQN(fromTable, database);
		long start = System.currentTimeMillis();
		Metadata metadata = Metadata.getMetadata(table);
		
		if (metadata == null) {
			throw new DBRuntimeException("Table " + table + " does not exist.");
		}
		
		metadata.readLock();
		
		try {
			
			// Normalize the where clause

			Expression where = plainSelect.getWhere();
			
			if (where == null) {
				throw new DBRuntimeException("The select must contain a where clause");
			}

			if (metadata.isQueue() && !isValidQueueQuery(where, metadata, true)) {
				throw new DBRuntimeException("The where clause is not supported for queries.");
			}
			
			// Normalize the parse tree then dp the actual search
			
			Expression normalized = new ExpressionNormalizer().normalize(where);
			ExpressionEvaluator evaluator = new ExpressionEvaluator(table);
			List<String> selectColumns = buildSelectList(plainSelect.getSelectItems());
			DocumentSet set = evaluator.evaluateWhere(normalized, selectColumns, parameters, Long.MAX_VALUE, false);	
			
			if (plainSelect.getGroupBy() == null) {
				processSelectItems(set, plainSelect.getSelectItems(), evaluator, parameters);
			}
			
			MetricsLogger.getInstance().logSelect(sqlString, System.currentTimeMillis() - start, set.size());
			MetricsLogger.getInstance().logGet();
			
			return set;
		} finally {
			metadata.unlock();
		}
		
	}

	static DocumentSet update(Update update, String sqlString, String targetShard, String database, String user, String password, List<Comparable<?>> parameters) throws Exception {
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
			return SQLUtils.forwardExecuteQuery(database, shard, sqlString, user, password, parameters);
		}
		
		DocumentSet set = updateLocal(update, sqlString, database, user, password, parameters);
		
		if (shard.equals(Client.WILDCARD) && targetShard.equals(Client.WILDCARD)) {
			Limit limitClause = update.getLimit();
			long limit = getLimit(limitClause, table);

			for (DocumentSet other : SQLUtils.broadcastExecuteQuery(database, update, limitClause, limit - set.size(), user, password, parameters)) {
				set.addAll(other);
			}
		}
		
		return set;
		
	}
	
	@SuppressWarnings("resource")
	static DocumentSet updateLocal(Update update, String sqlString, String database, String user, String password, List<Comparable<?>> parameters) {
		long start = System.currentTimeMillis();
		String table = SQLUtils.getFQN(update.getTable(), database);
		DocumentSet docs = null;
		boolean lock = false;
		Metadata metadata = Metadata.getMetadata(table);
		
		if (metadata == null) {
			throw new DBRuntimeException("Table " + update.getTable().getName() + " does not exist.");
		}
		
		metadata.readLock();
		BackupHandler.getInstance().startUpdate();
		
		try {			
			ReturningClause returnClause = update.getReturningClause();
			
			// Validate the update clauses
			
			for (UpdateSet set : update.getUpdateSets()) {
				List<Column> columns = set.getColumns();
				
				for (int idx = 0; idx < columns.size(); idx++) {
					String column = columns.get(idx).getFullyQualifiedName();
					String prefix = column.contains(".") ? column.substring(0, column.indexOf('.')) : column;					
					
					if (metadata.getColumn(prefix) == null) {
						throw new DBRuntimeException(column + " is not a valid column");
					} else if (metadata.getPrimaryKeyFields().contains(column)) {
						throw new DBRuntimeException("Primary field columns cannot be changed");
					}
				}
			}
			
			// Find the documents that need to be updated
			
			ExpressionEvaluator evaluator = new ExpressionEvaluator(table);
			Expression where = update.getWhere();
			long limit = getLimit(update.getLimit(), table);
			Expression normalized = new ExpressionNormalizer().normalize(where);
			
			lock = !isPrimaySearch(metadata, normalized);
			docs =  trim(evaluator.evaluateWhere(normalized, null, parameters, limit, lock), update.getLimit(), evaluator);
			
			// Update the documents and create a DocumentSet with all of the new documents
			
			DocumentSet newDocs = new DocumentSet();
			
			for (Document dod : docs) {
				Document newDoc = new Document(dod);
				
				for (UpdateSet set : update.getUpdateSets()) {
					List<Column> columns = set.getColumns();
					ExpressionList<?> values = set.getValues();

					for (int idx = 0; idx < columns.size(); idx++) {
						String column = columns.get(idx).getFullyQualifiedName();
						int dot = column.indexOf('.');
						ColumnInfo info = dot == -1 ? metadata.getColumn(column) : metadata.getColumn(column.substring(0, dot));
						Object value = evaluator.evaluate(values.get(idx), dod, parameters);
						
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
				
				newDocs.add(newDoc);
				
				QueryManager.getInstance().update(table, dod, newDoc);	
			}
			
			
			
			return returnClause == null || returnClause.isEmpty() 
				   ? new DocumentSet()
				   : processSelectItems(newDocs, returnClause, evaluator, parameters);
			
		} catch (DBRuntimeException exc) {
			throw exc;
		} catch (Exception exc) {
			throw new DBRuntimeException("The update failed " + exc.getMessage(), exc);
		} finally {
			if (lock && docs != null) {
				QueryManager.getInstance().unlockPrimaryAllKeys(table, docs);
			}
			
			metadata.unlock();
			BackupHandler.getInstance().endUpdate();
			MetricsLogger.getInstance().logUpdate();
			MetricsLogger.getInstance().logSelect(sqlString, System.currentTimeMillis() - start, 1);
		}
	}
	
	private static DocumentSet trim(DocumentSet set, Limit limitClause, ExpressionEvaluator evaluator) {
		if (limitClause == null || limitClause.getRowCount() == null) {
			return set;
		}
		
		
		long limit = (long)evaluator.evaluate(limitClause.getRowCount(), Long.class);
		
		if (limit > set.size()) {
			return set;
		}
		
		DocumentSet result = new DocumentSet();
		Iterator<Document> iterator = set.iterator();
		
		while (result.size() < limit && iterator.hasNext()) {
			result.add(iterator.next());
		}
		
		return result;

	}
	
	static Key getGroupByKey(Document doc, ExpressionList<?> groupByExpressions, ExpressionEvaluator evaluator, List<Comparable<?>> parameters) {
		List<Comparable<?>> keys = new ArrayList<>();
		
		for (Object obj :groupByExpressions) {			
			keys.add(evaluator.evaluate((Expression)obj, doc, parameters, Comparable.class));
		}
		
		return new Key(keys);
	}
	

}
