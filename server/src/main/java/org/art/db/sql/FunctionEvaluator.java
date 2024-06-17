package org.art.db.sql;

import java.util.Calendar;
import java.util.Date;
import java.util.List;

import org.art.db.common.Converter;
import org.art.db.common.DBRuntimeException;
import org.art.db.common.Document;
import org.art.db.common.GUID;
import org.art.db.storage.StorageManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FunctionEvaluator {
	
	@SuppressWarnings("unused")
	private static final Logger logger = LoggerFactory.getLogger(FunctionEvaluator.class);
	
	@SuppressWarnings({ "unchecked", "rawtypes" })
	static Object evaluate(String table, Document document, String name, List<Object> parameters) throws DBRuntimeException {
		Number number;
		Number first;
		Number second;
		List   list;
		int start;
		int end;
		String str;
		Calendar cal;

		try {
			switch (name.toLowerCase()) {
			case "abs":
				number = (Number)validate(name, parameters, 0, Number.class);

				if (number instanceof Float || number instanceof Double) {
					return Math.abs(number.doubleValue());
				} else {
					return Math.abs(number.longValue());
				}

			case "add":
				list = (List)validate(name, parameters, 0, List.class);
				
				if (parameters.size() == 2) {
					list.add(validate(name, parameters, 1, Integer.class));
				} else {
					list.add(validate(name, parameters, 1, Integer.class), validate(name, parameters, 2, Integer.class));
				}

				return list;
				
			case "date":
				cal = toCalendar(validate(name, parameters, 0, Date.class));
				
				return Long.valueOf(cal.get(Calendar.DAY_OF_MONTH));
				
			case "day":
				cal = toCalendar(validate(name, parameters, 0, Date.class));
				
				return Long.valueOf(cal.get(Calendar.DAY_OF_WEEK));
				
			case "hours":
				cal = toCalendar(validate(name, parameters, 0, Date.class));
				
				return Long.valueOf(cal.get(Calendar.HOUR_OF_DAY));	
				
			case "lower":
				return validate(name, parameters, 0, String.class).toLowerCase();

			case "lease":
				String owner = validate(name, parameters, 0, String.class);
				Number count = validate(name, parameters, 1, Number.class);
				Number timeout = parameters.size() == 3 ? validate(name, parameters, 2, Number.class) : 0;
				return StorageManager.getInstance().getTableManager(table).lease(owner, count.intValue(), timeout.longValue());
				
			case "max":
				first = validate(name, parameters, 0, Number.class);
				second = validate(name, parameters, 1, Number.class);

				if (first instanceof Float || first instanceof Double) {
					return Math.max(first.doubleValue(), second.doubleValue());
				} else {
					return Math.max(first.longValue(), second.longValue());
				}

			case "min":
				first = (Number)validate(name, parameters, 0, Number.class);
				second = (Number)validate(name, parameters, 1, Number.class);

				if (first instanceof Float || first instanceof Double) {
					return Math.min(first.doubleValue(), second.doubleValue());
				} else {
					return Math.min(first.longValue(), second.longValue());
				}
				
			case "minutes":
				cal = toCalendar(validate(name, parameters, 0, Date.class));
				
				return Long.valueOf(cal.get(Calendar.MINUTE));
				
			case "month":
				cal = toCalendar(validate(name, parameters, 0, Date.class));
				
				return Long.valueOf(cal.get(Calendar.MONTH));
			
			case "now":
				return new Date(System.currentTimeMillis());
				
			case "remove":
				list = (List)validate(name, parameters, 0, List.class);
				start = validate(name, parameters, 1, Integer.class);

				list.remove(start);

				return list;

			case "seconds":
				cal = toCalendar(validate(name, parameters, 0, Date.class));
				
				return Long.valueOf(cal.get(Calendar.SECOND));
								
			case "sublist":
				list = (List)validate(name, parameters, 0, List.class);
				start = validate(name, parameters, 1, Integer.class);
				end = validate(name, parameters, 2, Integer.class);

				return list.subList(start, end);

			case "substring":
				str = validate(name, parameters, 0, String.class);
				start = validate(name, parameters, 1, Integer.class);
				
				if (parameters.size() == 2) {
					return str.substring(start);
				}else {
					end = validate(name, parameters, 2, Integer.class);

					return str.substring(start, end);
				}

			case "time":
				cal = toCalendar(validate(name, parameters, 0, Date.class));
				
				return cal.getTimeInMillis();
					
			case "uid":
				return new GUID();
				
			case "upper":
				return validate(name, parameters, 0, String.class).toUpperCase();
				
			case "year":
				cal = toCalendar(validate(name, parameters, 0, Date.class));
				
				return Long.valueOf(cal.get(Calendar.YEAR));	
				
			default:
				throw new DBRuntimeException(name + " is not supported.");
			}
		} catch (DBRuntimeException exc) {
			throw exc;
		} catch (Exception exc) {
			throw new DBRuntimeException(name + "got an exception.", exc);
		}

	}
	
	private static <T> T validate(String name, List<Object> parameters, int index, Class<T> clazz) {
		if (index >= parameters.size()) {
			throw new DBRuntimeException("The call to " + name + " is missing one or more parameters");
		}
		
		try {
			return clazz.cast(Converter.convert(parameters.get(index), clazz));
		} catch (Exception exc) {
			throw new DBRuntimeException("The call to " + name + " has an invalid parameter.");
		}
	}
	
	static Calendar toCalendar(Date date){ 
		  Calendar cal = Calendar.getInstance();
		  cal.setTime(date);
		  return cal;
		}

}
