package org.art.db.client.dao;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.art.db.client.dao.BeanMetaData.Property;
import org.art.db.common.Converter;
import org.art.db.common.DBNull;
import org.art.db.common.Document;

public class BeanMapper {

	@SuppressWarnings("unchecked")
	static Document toDocument(Object bean) {
		if (bean instanceof Document doc) {
			return doc;
		}
		
		Document doc = new Document();
		
		if (bean instanceof Map) {
			Map<String, Object> map = (Map<String, Object>)bean;
			
			for (Map.Entry<String, Object> entry : map.entrySet()) {
				String name = entry.getKey();
				Object value = entry.getValue();
				
				doc.put(name, value instanceof Map ? toDocument(value) : value);
			}	
			
			return doc;
		}
		
		BeanMetaData metadata = BeanMetaData.getMetaData(bean.getClass());
		
		for (Property prop : metadata.getProperties(x -> !x.hasAnnotation(Transient.class))) {
			try {
				String column = prop.getColumn();
				Object value = prop.get(bean);

				if (value == null) {
					doc.put(column, DBNull.NULL);
				} else if (value instanceof Map) {
					doc.put(column, toDocument(value));
				} else if (value instanceof List list) {
					List<Document> docs = new ArrayList<>();
					
					for (Object item : list) {
						docs.add(toDocument(item));
					}
					
					doc.put(column, docs);
				} else {
					doc.put(column, Converter.convert(value, prop.type()));
				}
			} catch (Exception exc) {
			}
		}

		return doc;
	}
	
	static Object toBean(Document doc, Class<?> clazz) throws Exception {
		if (clazz == Document.class) {
			return doc;
		}
		
		BeanMetaData metadata = BeanMetaData.getMetaData(clazz);
		
		if (clazz.isAssignableFrom(Map.class)) {
			Map<String, Object> map = new HashMap<>();
			
			for (Map.Entry<String, Object> entry : doc) {
				Property prop = metadata.getProperty(entry.getKey());
				Object value = entry.getValue();
				
				if (prop != null) {
					map.put(prop.name(), value instanceof Document ? toBean((Document)value, Map.class) : value);
				}
			}
			
			return map;
		}
		
		Subclasses subclasses = clazz.getAnnotation(Subclasses.class);
		
		if (subclasses != null) {
			for (Property prop : metadata.getProperties(x -> x.hasAnnotation(Discriminator.class))) {
				Object value = (String)doc.get(prop.name());
				
				if (value == DBNull.NULL) {
					throw new Exception("The descriminator field is null");
				}
				
				for (Subclass sub : subclasses.value()) {
					if (sub.value().equals(value)) {
						clazz = sub.subclass();
						break;
					}
				}
			}
			
		}
		
		Constructor<?> cons = clazz.getConstructor();
		Object result = cons.newInstance();
		
		for (Map.Entry<String, Object> entry : doc) {
			Property prop = metadata.getPropertyByColumn(entry.getKey());
			Object value = entry.getValue();
			
			if (prop == null) {
				System.out.println("key " + entry.getKey());
			}
			if (value == DBNull.NULL) {
				continue;
			} else if (value instanceof Document) {
				prop.set(result, toBean((Document)value, Map.class));
			} else if (value instanceof List list) {
				List<Object> beanList = new ArrayList<>();
				
				for (Object item : list) {
					beanList.add(toBean((Document)item, BeanMetaData.getMemberType(prop.genericType())));
				}
				
				prop.set(result, beanList);
			} else {
				prop.set(result, Converter.convert(value, prop.type()));
			}
		}
		
		return result;

	}
}
