package org.art.db.client.dao;

import java.lang.reflect.Proxy;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.art.db.client.Client;
import org.art.db.client.dao.BeanMetaData.Property;
import org.art.db.common.DBRuntimeException;
import org.art.db.common.TypedOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.lang.reflect.*;

/**
 * This class does the first phase of compiling the DAO's.  It scans the classpath,
 * finds the DAO interfaces, and generates a proxy and a bean descriptor for each
 * DAO.
 * 
 * Note that this class implements BeanDefinitionRegistryPostProcessor.  This means
 * that it is called at the very beginning of the start up process.  It cannot
 * have @Autowired fields or a @PostConstruct method.  This means that it cannot
 * instantiate or configure the adapters.
 * 
 * The adapters are instantiated, configured, and saved in the proxies by
 * another configuration class that runs later in the startup process.
 * @author artgrand
 *
 */

public class DAOCompiler {
	
	@SuppressWarnings("unused")
	private static final Logger logger = LoggerFactory.getLogger(DAOCompiler.class);
	
	private ConcurrentMap<Class<?>, Object> delegates = new ConcurrentHashMap<>();
	
	private static final DAOCompiler instance = new DAOCompiler();
	
	public void clear() {
		this.delegates.clear();
	}
	@SuppressWarnings("unchecked")
	private <TInterface, TEntity> TInterface compile(Client client, Class<TInterface> iface) throws Exception  {
		
		Class<TEntity> entityClass = (Class<TEntity>)getEntityClass(iface);
		Entity entity = entityClass.getAnnotation(Entity.class);
		
		if (entity == null || entity.table().isBlank()) {
			throw new Exception("Class " + entityClass.getName() + " does not have a valid @Entity notation");
		}
		
		BeanMetaData metaData = BeanMetaData.getMetaData(entityClass);
		
		for (Property prop : metaData.getProperties(x -> !x.hasAnnotation(Transient.class))) {
			if (!TypedOutputStream.isSerializable(prop.toString())) {
				throw new Exception(prop.name() + " does not have a valid type");
			}
		}
		
		DAOHandler<TEntity> handler = new DAOHandler<>(client, iface, entityClass);	
		
		return iface.cast(Proxy.newProxyInstance(iface.getClassLoader(), new Class[] { iface }, handler));
			
	}
	
	public <TInterface, TEntity> TInterface getDAO(Client client, Class<TInterface> clazz) throws Exception {
		Object delegate = this.delegates.get(clazz);
		
		if (delegate == null) {
			TInterface dao = this.compile(client, clazz);
			
			this.delegates.put(clazz, dao);
			
			return dao;
		} else {
			return clazz.cast(delegate);
		}
		
	}
	
	private Class<?> getClassFromType(Type type) {
		if (type instanceof Class cls) {
			return cls;
		} else if (type instanceof ParameterizedType ptype) {
			return this.getClassFromType(ptype.getRawType());
		} else {
			throw new DBRuntimeException("Invalid type for DAO");
		}
	}
	private Class<?> getEntityClass(Class<?> daoClass) {
		ParameterizedType ptype = (ParameterizedType)daoClass.getGenericInterfaces()[0];

		return this.getClassFromType(ptype.getActualTypeArguments()[0]);
	
	}	
	
	public static DAOCompiler getInstance() {
		return instance;
	}
}
