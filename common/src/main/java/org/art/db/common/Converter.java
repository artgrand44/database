package org.art.db.common;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;



public class Converter {
	
	private static final Logger logger = LoggerFactory.getLogger(Converter.class);
	
	private static final Class<?>[] SUPPORTED_TYPES = {
			Boolean.class,
			Boolean.TYPE,
			Byte.class,
			Byte.TYPE,
			Character.class,
			Character.TYPE,
			Double.class,
			Double.TYPE,
			Float.class,
			Float.TYPE,
			Integer.class,
			Integer.TYPE,
			Long.class,
			Long.TYPE,
			BigDecimal.class,
			Short.class,
			Short.TYPE,
			Date.class,
			Timestamp.class,
			String.class,
	};
	
	private static final Map<String, Class<?>> TYPE_MAP;
	
	static {
		TYPE_MAP = new HashMap<>();
		
		for (Class<?> clazz : SUPPORTED_TYPES) {
			TYPE_MAP.put(clazz.getSimpleName(), clazz);
		}
	}
	
	@SuppressWarnings({ "unchecked", "rawtypes" })
	public static Object convert(Object obj, Class<?> clazz) throws Exception {
		if (obj == null || clazz.isAssignableFrom(obj.getClass()) || obj instanceof DBNull || obj instanceof Document) {
			return obj;
		}
		
		String s = obj.toString();

		if (clazz == Boolean.class || clazz == Boolean.TYPE) {
			return Boolean.parseBoolean(s);
		} else if (clazz == Byte.class || clazz == Byte.TYPE) {
			return Byte.parseByte(s);
		} else if ((clazz == Character.class || clazz == Character.TYPE) && !s.isEmpty()) {
			return s.charAt(0);
		} else if (clazz == Double.class || clazz == Double.TYPE) {
			return Double.parseDouble(s);
		} else if (clazz == Float.class || clazz == Float.TYPE) {
			return Float.parseFloat(s);
		} else if (clazz == Integer.class  || clazz == Integer.TYPE) {
			return Integer.parseInt(s);
		} else if (clazz == Long.class || clazz == Long.TYPE) {
			return Long.parseLong(s);
		} else if (clazz == BigDecimal.class) {
			return new BigDecimal(s);
		} else if (clazz == Short.class || clazz == Short.TYPE) {
			return Short.parseShort(s);
		} else if (clazz == Date.class) {
			return Date.valueOf(s);
		} else if (clazz == Timestamp.class) {
			return Timestamp.valueOf(s);
		} else if (clazz == Comparable.class && obj instanceof Comparable) {
			return obj;
		} else if (clazz == String.class) {
			return s;
		} else if (Enum.class.isAssignableFrom(clazz)) {
			return Enum.valueOf(((Class<? extends Enum>)clazz), s);
		} else {
			throw new Exception("Illegal field type");
		}
		
	}
	
	@SuppressWarnings("unchecked")
	public static Class<? extends Comparable<?>> getType(String shortName) {
		if (!TYPE_MAP.containsKey(shortName)) {
			logger.info("No type for " + shortName);
		}
		return (Class<? extends Comparable<?>>)TYPE_MAP.get(shortName);
	}

}
