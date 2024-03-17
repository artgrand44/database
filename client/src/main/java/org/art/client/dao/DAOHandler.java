package org.art.client.dao;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.art.client.Client;
import org.art.client.dao.BeanMetaData.Property;
import org.art.db.common.DBRuntimeException;
import org.art.db.common.Document;
import org.art.db.common.DocumentSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DAOHandler<TEntity> implements DAO<TEntity>, InvocationHandler {
	
	private static final Logger logger = LoggerFactory.getLogger(DAOHandler.class);
	
	private final Class<TEntity> entityClass;
	private final Client client;
	private final Map<Method, String> queries = new HashMap<>();
	
	DAOHandler(Client client, Class<?> iface, Class<TEntity> entityClass) throws Exception {
		this.client = client;
		this.entityClass = entityClass;
		
		for (Method method : iface.getMethods()) {
			if (method.getName().startsWith("find")) {
				Parser parser = new Parser(method, this.entityClass);
				
				this.queries.put(method, parser.getSQL());
				
				if (parser.getError() != null) {
					throw new DBRuntimeException(method.getName() + " is not a valid name " + parser.getError());
				}
			}
		}

	}

	@Override
	public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
		if (method.isDefault()) {
			return InvocationHandler.invokeDefault(proxy, method, args);
		}
		
		if (method.getName().startsWith("find")) {
			String query = this.queries.get(method);
			
			return this.select(query, args);
		}
		
		try {
			return method.invoke(this, args);
		} catch (InvocationTargetException exc) {
			Throwable cause = exc.getCause();
			
			throw cause instanceof DBRuntimeException ? exc : new DBRuntimeException(exc.getMessage(), exc);
		} catch (Exception exc) {
			logger.info("Proxy invoke threw exception " + exc.getMessage());
			throw new DBRuntimeException("Proxy invoke threw exception ", exc);
		}

	}

	@Override
	public void insert(TEntity bean) throws Exception {
		Class<?> clazz = bean.getClass();
		Entity entity = clazz.getAnnotation(Entity.class);
		
		if (entity == null) {
			throw new Exception("The bean class does not have an @Entity attribute");
		}
		
		Document doc = BeanMapper.toDocument(bean);
		List<String> fieldNames = new ArrayList<>();
		List<String> values = new ArrayList<>();
		List<Object> params = new ArrayList<>();
		
		for (Map.Entry<String, Object> entry : doc) {
			fieldNames.add(entry.getKey());
			params.add(entry.getValue());
			values.add("?");
		}
		
		String statement = "insert into  " + entity.table() + " (" + String.join(" , ", fieldNames) + ") values (" + String.join(" , ", values) + ")"; 
		
		this.client.execute(statement, params.toArray());
			
	}

	@Override
	public void delete(TEntity bean) throws Exception {
		Class<?> clazz = bean.getClass();
		BeanMetaData metaData = BeanMetaData.getMetaData(clazz);
		Entity entity = clazz.getAnnotation(Entity.class);
		
		if (entity == null) {
			throw new Exception("The bean class does not have an @Entity attribute");
		}
		
		Document doc = BeanMapper.toDocument(bean);
		List<Object> values = new ArrayList<>();
		
		List<String> comparisons = new ArrayList<>();
		
		for (Property prop : metaData.getProperties(x -> x.hasAnnotation(Id.class))) {
			String column = prop.getColumn();
			comparisons.add(column+ " = ?");
			values.add(doc.get(column));
		}
		
		String statement = "delete " + entity.table() + " where " + String.join(" and ", comparisons);
		
		this.client.execute(statement, values.toArray());
		
	}

	private List<Property> getBoundProperties(BeanMetaData metaData) {
		return metaData.getProperties(x -> !x.hasAnnotation(Transient.class));
	}
	
	private List<Property> getBoundProperties(BeanMetaData metaData, String[] names) throws Exception{
		List<Property> props = new ArrayList<>();
		
		for (String name : names) {
			Property prop = metaData.getProperty(name);
			
			if (prop.hasAnnotation(Transient.class)) {
				throw new Exception(name + " is not associated with a database column.");
			}
			
			props.add(prop);
		}
		
		return props;
	}
	
	@Override
	public void update(TEntity bean, String ... fields) throws Exception {
		Class<?> clazz = bean.getClass();
		BeanMetaData metaData = BeanMetaData.getMetaData(clazz);
		Entity entity = clazz.getAnnotation(Entity.class);
		
		if (entity == null) {
			throw new Exception("The bean class does not have an @Entity attribute");
		}
		
		Document doc = BeanMapper.toDocument(bean);
		List<String> updates = new ArrayList<>();
		List<Object> values = new ArrayList<>();
		List<Property> updatedProperties = fields.length == 0 
				                           ? this.getBoundProperties(metaData)
				                           : this.getBoundProperties(metaData, fields);
		
		for (Property prop : updatedProperties) {
			String column = prop.getColumn();
			Object value = doc.get(column);
			
			updates.add(column + " = ?");
			values.add(value);
		}
		
		List<String> comparisons = new ArrayList<>();
		
		for (Property prop : metaData.getProperties(x -> x.hasAnnotation(Id.class))) {
			comparisons.add(prop.name() + " = ?");
			values.add(doc.get(prop.name()));
		}
		
		String statement = "update " + entity.table() + " set " + String.join(",", updates) + " where " + String.join(" and ", comparisons);
		
		logger.info(statement);
		
		this.client.execute(statement, values.toArray());
				
	}

	@Override
	public Iterable<TEntity> select(String statement, Object... parameters) throws Exception {
		DocumentSet set = this.client.executeQuery(statement, parameters);
		
		return new BeanCollection<TEntity>(set, this.entityClass);
	}
	
}
