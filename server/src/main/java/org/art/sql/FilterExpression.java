
package org.art.sql;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.art.query.Filter;
import org.art.storage.Comparison;

import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.ExpressionVisitor;
import net.sf.jsqlparser.expression.operators.relational.ExpressionList;
import net.sf.jsqlparser.parser.ASTNodeAccessImpl;

public class FilterExpression extends ASTNodeAccessImpl implements Expression {

	private static final long serialVersionUID = 1L;


	
	private static final Map<String, Comparison> COMPARISONS  =
			Map.of("<",  Comparison.LT,
			       "<=", Comparison.LE,
				   ">",  Comparison.GT,
				   ">=", Comparison.GE,
				   "=",  Comparison.EQ,
				   "<>", Comparison.NE,
				   "IN", Comparison.IN,
				   "BETWEEN", Comparison.BETWEEN);
	
		
	private final List<Filter> filters = new ArrayList<>();
	
	FilterExpression() {
		
	}
	
	public FilterExpression(String field, String operator, Expression value) {
		this.filters.add(new Filter(getComparison(operator), field, value));
	}
	
	public FilterExpression(String field, String operator, ExpressionList<?> value) {
		this.filters.add(new Filter(getComparison(operator), field, value));
	}
	
	@Override
	public void accept(ExpressionVisitor expressionVisitor) {
		// TODO Auto-generated method stub
		
	}
	
	public void addFilter(String field, String operator, Expression value) {
		this.filters.add(new Filter(getComparison(operator), field, value));
	}
	
	private static Comparison getComparison(String operator) {
		StringBuilder builder = new StringBuilder();
		
		for (char chr : operator.toCharArray()) {
			if (!Character.isWhitespace(chr)) {
				builder.append(chr);
			}
		}
		
		Comparison comp = COMPARISONS.get(builder.toString());
		
		if (comp == null) {
			throw new IllegalStateException("Bad comparison");
		}
		
		return comp;
	}
	public List<Filter> getFilters() {
		return this.filters;
	}

	public void merge(FilterExpression that) {
		for (Filter filter : that.filters) {
			this.filters.add(filter);
		}
	}
	
	
	@Override
	public String toString() {
		StringBuilder builder = new StringBuilder();
		
		for (int idx = 0; idx < this.filters.size(); idx++) {
			builder.append(filters.get(idx).toString());
			
			if (idx < this.filters.size() - 1) {
				builder.append(" and ");
			}
		}
		
		return builder.toString();
	}
}
