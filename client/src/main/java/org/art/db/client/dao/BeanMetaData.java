package org.art.db.client.dao;

import java.beans.BeanInfo;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * A class for holding metadata about a POJO and it's properties.
 * Both fields are getter/setter pairs are treated as properties.
 * @author artgrand
 *
 */
public class BeanMetaData{
	
	private static final Logger logger = LoggerFactory.getLogger(BeanMetaData.class);
	
	private static Map<Class<?>, BeanMetaData> metaData = new ConcurrentHashMap<>();
	
	public static record Property(String name, Class<?> type, Type genericType, Member getter, Member setter, Map<Class<?>, Annotation> annotations) {
		
		public boolean hasAnnotation(Class<?> clazz) {
			return this.annotations.containsKey(clazz);
		}
		
		public boolean isCollection() {
			return Collection.class.isAssignableFrom(type);
		}
		
		public Object get(Object bean) throws Exception {
			if (this.getter == null) {
				throw new Exception("Property is write only.");
			} else if (this.getter instanceof Method method) {
				return method.invoke(bean);
			} else {
				return ((Field)this.getter).get(bean);
			}			
		}
		
		String getColumn() {
			Column column = this.getAnnotation(Column.class);
			
			return column == null ? this.name : column.value();
		}
		
		public <TAttribute> TAttribute getAnnotation(Class<TAttribute> clazz) {
			return clazz.cast(this.annotations.get(clazz));
		}
		
		public void set(Object bean, Object value) throws Exception {
			if (this.setter == null) {
				throw new Exception("Property is write only.");
			} else if (this.setter instanceof Method method) {
				method.invoke(bean, value);
			} else {
				((Field)this.setter).set(bean, value);
			}			
		}
		
	}
	
	private final Map<String, Property> properties = new HashMap<>();
	private final Map<String, Property> propertiesByColumn = new HashMap<>();
	
	private static final void addAnnotations(Map<Class<?>, Annotation> map, Annotation[] annotations) {
		for (Annotation annotation : annotations) {
			map.put(annotation.annotationType(), annotation);
		}
	}
	
	public BeanMetaData(Class<?> clazz) {
		try {
			BeanInfo info = Introspector.getBeanInfo(clazz);
			
			for (PropertyDescriptor prop : info.getPropertyDescriptors()) {
				Method readMethod = prop.getReadMethod();
				Method writeMethod = prop.getWriteMethod();
				Class<?> type = prop.getPropertyType();
				Map<Class<?>, Annotation> annotations = new HashMap<>();
				
				if (readMethod == null || writeMethod  == null) {
					continue;
				}
				
				addAnnotations(annotations, readMethod.getAnnotations());
				addAnnotations(annotations, writeMethod.getAnnotations());
								
				String name = prop.getName();
				
				Property property = new Property(name, type, readMethod.getGenericReturnType(), readMethod, writeMethod, annotations);
				
				this.properties.put(name, property);
			}
			
			for (Field field : clazz.getDeclaredFields()) {
				int mods = field.getModifiers();
				String name = field.getName();
				Property prop = this.properties.get(name);
				Map<Class<?>, Annotation> annotations = new HashMap<>();
				
				addAnnotations(annotations, field.getAnnotations());
				
				if (prop != null && !Modifier.isStatic(mods)) {
					prop.annotations.putAll(annotations);
				} else if (prop == null && Modifier.isPublic(mods) && !Modifier.isStatic(mods)) {
					Property property = new Property(name, field.getType(), field.getGenericType(), field, field, annotations);
						
					this.properties.put(field.getName(), property);
				} 
			}
			
			for (Property prop : this.properties.values()) {
				this.propertiesByColumn.put(prop.getColumn(), prop);
			}
		} catch (Exception exc) {
			logger.info("Exception getting relationships " + exc.getMessage());
		}
	}
	
	public static BeanMetaData getMetaData(Class<?> clazz) {
		BeanMetaData meta = metaData.get(clazz);

		if (meta == null) {
			meta = new BeanMetaData(clazz); 
			metaData.put(clazz, meta);
		}
		
		return meta;
	}
	
	public static Class<?> getMemberType(Type type) {
		if (type instanceof ParameterizedType ptype) {
			Type memberType = ptype.getActualTypeArguments()[0];
			
			while (memberType instanceof ParameterizedType mtype) {
				memberType = mtype.getRawType();
			}
			
			return (Class<?>)memberType;
		}
		
		return null;
		
	}
	
	public Property getProperty(String name) {
		return this.properties.get(name);
	}
	
	public Property getPropertyByColumn(String col) {
		return this.propertiesByColumn.get(col);
		
	}
	
	public Optional<Property> getProperty(Predicate<Property> test) {
		return this.properties.values().stream().filter(test).findFirst();
	}
	
	public Collection<Property> getProperties() {
		return this.properties.values();
	}
	
	public List<Property> getProperties(Predicate<Property> test) {
		return this.properties.values().stream().filter(test).toList();
	}
	
	

}
