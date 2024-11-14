package org.art.db.sql;

import java.util.List;

import org.art.db.common.Document;

import net.sf.jsqlparser.expression.Expression;

/**
 * An accumulator for creating sums, averages, etc. for a set of documents
 * @param <T>
 */
public abstract class Accumulator<T> {
	private final ExpressionEvaluator evaluator;
	private final Expression expression;
	private final List<Comparable<?>> parameters;
	private Class<T> clazz;
	
	protected Accumulator(ExpressionEvaluator evaluator, Expression expression, List<Comparable<?>> parameters, Class<T> clazz) {
		this.evaluator = evaluator;
		this.expression = expression;
		this.parameters = parameters;
		this.clazz = clazz;
	}
	
	public abstract void addValue(Object value) throws Exception;
	
	public abstract void clear();
	
	public abstract T compute();
	
	public void process(Document doc) throws Exception {
		T value = evaluator.evaluate(this.expression, doc, parameters, this.clazz);
		
		this.addValue(value);
	}
}
