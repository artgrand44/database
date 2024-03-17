package org.art.db.client.dao;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.art.db.client.dao.BeanMetaData.Property;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Parser  {
	
	@SuppressWarnings("unused")
	private static final Logger logger = LoggerFactory.getLogger(Parser.class);
	
	private static final Map<String, String> FILTERS = new HashMap<>();
	
	static {
		FILTERS.put("GreaterThan",      ">");
		FILTERS.put("LessThan",         "<");
		FILTERS.put("GreaterThanEqual", ">=");
		FILTERS.put("LessThanEqual",    "<=");
		FILTERS.put("Is",               "=");
		FILTERS.put("Equals",           "=");
		FILTERS.put("Like",             "<>");
	}
	
	private static final String EOF = "$eof";
	
	private static final List<String> KEYWORDS = Arrays.asList(new String[] { 			
		"find",
		"find",
		"Less",
		"Greater",
		"Than",
		"Is",
		"Equals",
		"Equal",
		"Like",
		"Desc",
		"Asc",
		"And",
		EOF,
	});
	
	private static final String UPPER = "\\p{Lu}|\\P{InBASIC_LATIN}";;
	
	private static final String LOWER = "\\p{Ll}";
	
	private static final String CAMEL_CASE_REGEX = "(?<!(^|[%u_$]))(?=[%u])|(?<!^)(?=[%u][%l])". //
			replace("%u", UPPER).replace("%l", LOWER);
	
	
	private static final Pattern CAMEL_CASE = Pattern.compile(CAMEL_CASE_REGEX);
	private String sql;
	private String error;
	private final BeanMetaData metaData;
	private final String table;

	Parser(Method method, Class<?> entityClass) {
		Entity entity = entityClass.getAnnotation(Entity.class);
		
		this.metaData = BeanMetaData.getMetaData(entityClass);
		this.table = entity == null ? null : entity.table();
		this.parse(method, entityClass);
	}

	private String decapitalize(String value) {
		return value.substring(0, 1).toLowerCase() + value.substring(1);
	}
	
	public String getError() {
		return this.error;
	}
	
	
	private String getColumn(String fieldName)  throws Exception {
		Property prop = this.metaData.getProperty(fieldName);
		
		if (prop == null) {
			throw new Exception(fieldName + " is not a valid property");			
		} else if (prop.hasAnnotation(Transient.class)) {
			throw new Exception(fieldName + " is not a bound property");
		}
		
		return prop.getColumn();
	}
	
	public String getSQL() {
		return this.sql;
	}
		
	private void parse(Method method, Class<?> entityClass) {
		String name = method.getName();
		
		if (this.table == null) {
			this.error = entityClass.getSimpleName() + " does not have a valid @Entity annotation.";
			return;
		}
				
		if (!Iterable.class.isAssignableFrom(method.getReturnType())) {
			this.error = name + " does not return an Iterable";
			return;
		}
		
		if (entityClass != BeanMetaData.getMemberType(method.getGenericReturnType())) {
			this.error = name + " does not return an Iterable<"  + entityClass.getSimpleName() + ">.";
			return;
		}		
		
		TokenStream  stream = new TokenStream(name);
		
		if (!stream.peek(1).equals("find")) {
			this.error = "Missing keyword 'find'";
			return;
		}
		
		stream.advance();
		
		if (stream.peek(1).equals("By")) {
			stream.advance();
		} else {
			this.error = "Missing keyword 'By'";
			return;
		}
	
		try {
			String conditions = this.parseFilterList(stream);

			if (StringUtils.isAllBlank(conditions)) {
				throw new Exception("The where clause is missing");

			}

			String orderBy = "";

			if (stream.peek(1).equals("By")) {
				stream.advance();
				orderBy = " order by " + this.parseOrderBy(stream);
			}

			this.sql = "select * from " + this.table + " where " + conditions + orderBy;
		} catch (Exception exc) {
			this.error = exc.getMessage();
		}

	}
	
	private String parseField(TokenStream stream) throws Exception {
		String field = "";
		
		for (;;) {
			String first = stream.peek(1);
			String second = stream.peek(2);
			
			if (KEYWORDS.contains(first) || (first + second).equals("OrderBy")) {
				break;
			}
			
			field += first;
			stream.advance();
		}
		
		return field.length() == 0 ? null : this.getColumn(decapitalize(field));
		
	}
	
	private String parseFilter(TokenStream stream) throws Exception {
		String field = parseField(stream);
		String operator;
		
		if (field == null) {
			return null;
		}
		
		if ((operator = parseOperator(stream)) == null) {
			return field + " = ?";
		} else {
			return field + FILTERS.get(operator) + "?";
		}
	}
	
	private String parseFilterList(TokenStream stream) throws Exception {
		List<String> comparisions = new ArrayList<>();
		String comparison; 
		
		while ((comparison = parseFilter(stream)) != null) {
			comparisions.add(comparison);
		}
		
		return String.join(" and ", comparisions);
	}
	
	private String parseOperator(TokenStream stream) {
		String first = stream.peek(1);
		String second = stream.peek(2);
		String third = stream.peek(3);
		
		if (FILTERS.containsKey(first + second + third)) {
			stream.advance(3);
			return first + second + third;
		} else if (FILTERS.containsKey(first + second)) {
			stream.advance(2);
			return first + second;
		} else if (FILTERS.containsKey(first)) {
			stream.advance(1);
			return first;
		} else {
			return null;
		}
	}

	private String parseOrderBy(TokenStream stream) throws Exception {
		List<String> sorts = new ArrayList<>();
		
		while (stream.peek(1).equals("Order") && stream.peek(2).equals("By")) {
			stream.advance(2);
			
			String field = parseField(stream);
			String direction = stream.peek(1);
			
			if (field != null && direction.equals("Asc")) {
				stream.advance();
				sorts.add(field + " ASC");
			} else if (field != null && direction.equals("Desc")) {
				stream.advance();
				sorts.add(field + " DESC");
			} else if (field != null) {
				sorts.add(field + " ASC");
			}					
		}
		
		return sorts.isEmpty() ? null :  String.join(",", sorts);
		
	}
	
	static class TokenStream  {
		private final List<String> tokens;
		private int currentIndex = -1;
		
		TokenStream(String source) {
			this.tokens = splitCamelCase(source);
		}
		
		private static List<String> splitCamelCase(String source) {

			String[] parts = CAMEL_CASE.split(source);
			List<String> result = new ArrayList<>(parts.length);

			for (String part : parts) {
				result.add(part);
			}

			return result;
		}
		
		public void advance() {
			this.currentIndex++;
		}
		
		public void advance(int count) {
			this.currentIndex += count;
		}
		
		String peek(int skip) {
			return this.currentIndex + skip < this.tokens.size() ? this.tokens.get(this.currentIndex + skip) : EOF;
				
		}
		
	}
}
