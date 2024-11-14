package org.art.db.sql;

import java.util.List;
import java.util.Set;

import org.art.db.common.Converter;
import org.art.db.storage.Metadata;

import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.schema.Column;

public class Accumulators {
	
	static Set<String> accumulatorFunctions = Set.of("average", "count", "max", "min", "sum");
	
	
	public static class Count extends Accumulator<Long> {
		long count;
		
		Count(ExpressionEvaluator evaluator, Column col, List<Comparable<?>> parameters) {
			super(evaluator, col, parameters, Long.class);
		}
		
		@Override
		public void addValue(Object value) throws Exception {
			this.count++;
		}

		@Override
		public void clear() {
			this.count = 0;
		}
		
		@Override
		public Long compute() {
			return this.count;
		}
		
	}

	public static class DoubleAverage extends Accumulator<Double> {
		
		double sum = 0;
		int count;
		
		DoubleAverage(ExpressionEvaluator evaluator, Column col, List<Comparable<?>> parameters) {
			super(evaluator, col, parameters, Double.class);
		}
				
		@Override
		public void addValue(Object value) throws Exception {
			this.sum += (Double)Converter.convert(value, Double.class);
			this.count++;
		}

		@Override
		public void clear() {
			this.sum = 0;
			this.count = 0;
		}
		
		@Override
		public Double compute() {
			return this.sum / this.count;
		}
		
	}
	
	public static class DoubleMax extends Accumulator<Double> {
		double max = Double.MIN_VALUE;;
		
		DoubleMax(ExpressionEvaluator evaluator, Column col, List<Comparable<?>> parameters) {
			super(evaluator, col, parameters, Double.class);
		}
		
		@Override
		public void addValue(Object value) throws Exception {
			this.max = Math.min(this.max, (Double)Converter.convert(value, Double.class));
		}

		@Override
		public void clear() {
			this.max = 0;
		}
		
		@Override
		public Double compute() {
			return this.max;
		}
		
	}

	
	public static class DoubleMin extends Accumulator<Double> {
		double min = Double.MAX_VALUE;;
		
		DoubleMin(ExpressionEvaluator evaluator, Column col, List<Comparable<?>> parameters) {
			super(evaluator, col, parameters, Double.class);
		}
		
		@Override
		public void addValue(Object value) throws Exception {
			this.min = Math.min(this.min, (Double)Converter.convert(value, Double.class));
		}

		@Override
		public void clear() {
			this.min = 0;
		}
		
		@Override
		public Double compute() {
			return this.min;
		}
		
	}

	public static class DoubleSum extends Accumulator<Double> {
		double sum = 0;
		
		DoubleSum(ExpressionEvaluator evaluator,Column col, List<Comparable<?>> parameters) {
			super(evaluator, col, parameters, Double.class);
		}
		
		@Override
		public void addValue(Object value) throws Exception {
			this.sum += (Double)Converter.convert(value, Double.class);
			
		}
		
		@Override
		public void clear() {
			this.sum = 0;
		}
		
		@Override
		public Double compute() {
			return this.sum;
		}
		
	}
	
	public static class FirstValue extends Accumulator<Object> {
		Object value = null;
		boolean first = true;
		
		FirstValue(ExpressionEvaluator evaluator, Expression expression, List<Comparable<?>> parameters) {
			super(evaluator, expression, parameters, Object.class);
		}
		
		
		@Override
		public void addValue(Object value) throws Exception {
			if (this.first) {
				this.first = false;
				this.value = value;
			}
		}
		
		@Override
		public void clear() {
			this.first = true;
			this.value = null;
		}
		
		@Override
		public Object compute() {
			return this.value;
		}
		
	}

	public static class LongAverage extends Accumulator<Long> {
		long sum = 0;
		int count;
		
		LongAverage(ExpressionEvaluator evaluator, Column col, List<Comparable<?>> parameters) {
			super(evaluator, col, parameters, Long.class);
		}
		
		
		@Override
		public void addValue(Object value) throws Exception {
			this.sum += (Double)Converter.convert(value, Long.class);
			this.count++;
		}
		
		@Override
		public void clear() {
			this.sum = 0;
			this.count = 0;
		}
		
		@Override
		public Long compute() {
			return this.sum / this.count;
		}
		
	}
	
	public static class LongMax extends Accumulator<Long> {
		long max = Long.MIN_VALUE;
		
		LongMax(ExpressionEvaluator evaluator, Column col, List<Comparable<?>> parameters) {
			super(evaluator, col, parameters, Long.class);
		}
			
		@Override
		public void addValue(Object value) throws Exception {
			this.max = Math.min(this.max, (Long)Converter.convert(value, Long.class));
		}
		
		@Override
		public void clear() {
			this.max = 0;
		}
		
		@Override
		public Long compute() {
			return this.max;
		}
		
	}

	
	public static class LongMin extends Accumulator<Long> {
		long min = Long.MAX_VALUE;
		
		LongMin(ExpressionEvaluator evaluator, Column col, List<Comparable<?>> parameters) {
			super(evaluator, col, parameters, Long.class);
		}
		
		@Override
		public void addValue(Object value) throws Exception {
			this.min = Math.min(this.min, (Long)Converter.convert(value, Long.class));
		}

		@Override
		public void clear() {
			this.min = 0;
		}
		
		@Override
		public Long compute() {
			return this.min;
		}
		
	}


	public static class LongSum extends Accumulator<Long> {
		long sum = 0;
		
		LongSum(ExpressionEvaluator evaluator, Column col, List<Comparable<?>> parameters) {
			super(evaluator, col, parameters, Long.class);
		}
		
		@Override
		public void addValue(Object value) throws Exception {
			this.sum += (Long)Converter.convert(value, Long.class);
			
		}

		@Override
		public void clear() {
			this.sum = 0;
		}
		
		@Override
		public Long compute() {
			return this.sum;
		}
		
	}
	
	public static Accumulator<?> getAccumulator(ExpressionEvaluator evaluator, Expression exp, List<Comparable<?>> parameters, Metadata metadate)  {
		
		if (exp instanceof Function func && func.getParameters().size() == 1 && func.getParameters().get(0) instanceof Column col) {
			String name = col.getColumnName();
			Class<?> clazz = metadate.getColumn(name).type();
			boolean isFloat = clazz == Float.class || clazz == Double.class;

			switch(func.getName().toLowerCase()) {
			case "average": 
				return isFloat ? new DoubleAverage(evaluator, col, parameters) : new LongAverage(evaluator, col, parameters);
			case "count": 
				return new Count(evaluator, col, parameters);
			case "max": 
				return isFloat ? new DoubleMax(evaluator, col, parameters) : new LongMax(evaluator, col, parameters);
			case "min": 
				return isFloat ? new DoubleMin(evaluator, col, parameters) : new LongMin(evaluator, col, parameters);
			case "sum": 
				return isFloat ? new DoubleSum(evaluator, col, parameters) : new LongSum(evaluator, col, parameters);

			}
		}
		
		return new FirstValue(evaluator, exp, parameters);
	} 
	
}
