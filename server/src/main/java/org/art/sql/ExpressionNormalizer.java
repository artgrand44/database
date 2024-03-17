package org.art.sql;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.sf.jsqlparser.expression.AllValue;
import net.sf.jsqlparser.expression.AnalyticExpression;
import net.sf.jsqlparser.expression.AnyComparisonExpression;
import net.sf.jsqlparser.expression.ArrayConstructor;
import net.sf.jsqlparser.expression.ArrayExpression;
import net.sf.jsqlparser.expression.BinaryExpression;
import net.sf.jsqlparser.expression.CaseExpression;
import net.sf.jsqlparser.expression.CastExpression;
import net.sf.jsqlparser.expression.CollateExpression;
import net.sf.jsqlparser.expression.ConnectByRootOperator;
import net.sf.jsqlparser.expression.DateTimeLiteralExpression;
import net.sf.jsqlparser.expression.DateValue;
import net.sf.jsqlparser.expression.DoubleValue;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.ExpressionVisitor;
import net.sf.jsqlparser.expression.ExtractExpression;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.HexValue;
import net.sf.jsqlparser.expression.IntervalExpression;
import net.sf.jsqlparser.expression.JdbcNamedParameter;
import net.sf.jsqlparser.expression.JdbcParameter;
import net.sf.jsqlparser.expression.JsonAggregateFunction;
import net.sf.jsqlparser.expression.JsonExpression;
import net.sf.jsqlparser.expression.JsonFunction;
import net.sf.jsqlparser.expression.KeepExpression;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.MySQLGroupConcat;
import net.sf.jsqlparser.expression.NextValExpression;
import net.sf.jsqlparser.expression.NotExpression;
import net.sf.jsqlparser.expression.NullValue;
import net.sf.jsqlparser.expression.NumericBind;
import net.sf.jsqlparser.expression.OracleHierarchicalExpression;
import net.sf.jsqlparser.expression.OracleHint;
import net.sf.jsqlparser.expression.OracleNamedFunctionParameter;
import net.sf.jsqlparser.expression.OverlapsCondition;
import net.sf.jsqlparser.expression.Parenthesis;
import net.sf.jsqlparser.expression.RangeExpression;
import net.sf.jsqlparser.expression.RowConstructor;
import net.sf.jsqlparser.expression.RowGetExpression;
import net.sf.jsqlparser.expression.SignedExpression;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.expression.TimeKeyExpression;
import net.sf.jsqlparser.expression.TimeValue;
import net.sf.jsqlparser.expression.TimestampValue;
import net.sf.jsqlparser.expression.TimezoneExpression;
import net.sf.jsqlparser.expression.TranscodingFunction;
import net.sf.jsqlparser.expression.TrimFunction;
import net.sf.jsqlparser.expression.UserVariable;
import net.sf.jsqlparser.expression.VariableAssignment;
import net.sf.jsqlparser.expression.WhenClause;
import net.sf.jsqlparser.expression.XMLSerializeExpr;
import net.sf.jsqlparser.expression.operators.arithmetic.Addition;
import net.sf.jsqlparser.expression.operators.arithmetic.BitwiseAnd;
import net.sf.jsqlparser.expression.operators.arithmetic.BitwiseLeftShift;
import net.sf.jsqlparser.expression.operators.arithmetic.BitwiseOr;
import net.sf.jsqlparser.expression.operators.arithmetic.BitwiseRightShift;
import net.sf.jsqlparser.expression.operators.arithmetic.BitwiseXor;
import net.sf.jsqlparser.expression.operators.arithmetic.Concat;
import net.sf.jsqlparser.expression.operators.arithmetic.Division;
import net.sf.jsqlparser.expression.operators.arithmetic.IntegerDivision;
import net.sf.jsqlparser.expression.operators.arithmetic.Modulo;
import net.sf.jsqlparser.expression.operators.arithmetic.Multiplication;
import net.sf.jsqlparser.expression.operators.arithmetic.Subtraction;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.conditional.OrExpression;
import net.sf.jsqlparser.expression.operators.conditional.XorExpression;
import net.sf.jsqlparser.expression.operators.relational.Between;
import net.sf.jsqlparser.expression.operators.relational.ComparisonOperator;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.expression.operators.relational.ExistsExpression;
import net.sf.jsqlparser.expression.operators.relational.ExpressionList;
import net.sf.jsqlparser.expression.operators.relational.FullTextSearch;
import net.sf.jsqlparser.expression.operators.relational.GeometryDistance;
import net.sf.jsqlparser.expression.operators.relational.GreaterThan;
import net.sf.jsqlparser.expression.operators.relational.GreaterThanEquals;
import net.sf.jsqlparser.expression.operators.relational.InExpression;
import net.sf.jsqlparser.expression.operators.relational.IsBooleanExpression;
import net.sf.jsqlparser.expression.operators.relational.IsDistinctExpression;
import net.sf.jsqlparser.expression.operators.relational.IsNullExpression;
import net.sf.jsqlparser.expression.operators.relational.JsonOperator;
import net.sf.jsqlparser.expression.operators.relational.LikeExpression;
import net.sf.jsqlparser.expression.operators.relational.Matches;
import net.sf.jsqlparser.expression.operators.relational.MemberOfExpression;
import net.sf.jsqlparser.expression.operators.relational.MinorThan;
import net.sf.jsqlparser.expression.operators.relational.MinorThanEquals;
import net.sf.jsqlparser.expression.operators.relational.NotEqualsTo;
import net.sf.jsqlparser.expression.operators.relational.ParenthesedExpressionList;
import net.sf.jsqlparser.expression.operators.relational.RegExpMatchOperator;
import net.sf.jsqlparser.expression.operators.relational.SimilarToExpression;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.select.AllColumns;
import net.sf.jsqlparser.statement.select.AllTableColumns;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.Select;

public class ExpressionNormalizer implements ExpressionVisitor {
	
	@SuppressWarnings("unused")
	private static final Logger logger = LoggerFactory.getLogger(ExpressionNormalizer.class);
	
	private static Map<String, String> inverseOperators =
			Map.of("<", ">=",
				   "<=", ">",
				   ">", "<=",
				   ">=", "<",
				   "=", "=");
	
	private Expression replacement;
	
	Expression normalize(Expression exp) {
		
		if (exp instanceof Parenthesis paren) {
			Expression content = this.normalize(paren.getExpression());
			
			if (content instanceof FilterExpression field) {
				return field;
			} else {
				paren.setExpression(content);
				return paren;
			}
		}
		
		exp.accept(this);
		
		Expression result = replacement == null ? exp : this.replacement;
		
		this.replacement = null;
		
		return result;
	}
	
	private void normalizeBinaryExpression(BinaryExpression binary) {
		Expression newLeft = this.normalize(binary.getLeftExpression());
		Expression newright = this.normalize(binary.getRightExpression());
		
		binary.setLeftExpression(newLeft);
		binary.setRightExpression(newright);		
	}
	
	private void normalizeComparison(ComparisonOperator comparison) {
		String field;
		String operator;
		Expression exp;
		
		if (comparison.getLeftExpression() instanceof Column && comparison.getRightExpression() instanceof Column) {
			return;
		}
		
		if (comparison.getLeftExpression() instanceof Column column) {
			field = column.getFullyQualifiedName();
			operator = comparison.getStringExpression();
			exp = comparison.getRightExpression();
			
			this.replacement = new FilterExpression(field, operator, exp);
		} else if (comparison.getRightExpression() instanceof Column column) {
			field = column.getFullyQualifiedName();
			operator = inverseOperators.get(comparison.getStringExpression());
			exp = comparison.getLeftExpression();
			
			this.replacement = new FilterExpression(field, operator, exp);
		}
			
	}
	
	@Override
	public void visit(BitwiseRightShift aThis) {
		this.normalize(aThis);
		
	}

	@Override
	public void visit(BitwiseLeftShift aThis) {
		this.normalizeBinaryExpression(aThis);
		
	}

	@Override
	public void visit(NullValue nullValue) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void visit(Function function) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void visit(SignedExpression signedExpression) {
		signedExpression.setExpression(this.normalize(signedExpression.getExpression()));
		
	}

	@Override
	public void visit(JdbcParameter jdbcParameter) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void visit(JdbcNamedParameter jdbcNamedParameter) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void visit(DoubleValue doubleValue) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void visit(LongValue longValue) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void visit(HexValue hexValue) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void visit(DateValue dateValue) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void visit(TimeValue timeValue) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void visit(TimestampValue timestampValue) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void visit(Parenthesis parenthesis) {
		parenthesis.setExpression(this.normalize(parenthesis.getExpression()));
		
	}

	@Override
	public void visit(StringValue stringValue) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void visit(Addition addition) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void visit(Division division) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void visit(IntegerDivision division) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void visit(Multiplication multiplication) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void visit(Subtraction subtraction) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void visit(AndExpression andExpression) {
		Expression left = this.normalize(andExpression.getLeftExpression());
		Expression right = this.normalize(andExpression.getRightExpression());
		
		if (left instanceof FilterExpression leftFilter && right instanceof FilterExpression rightFilter) {
			leftFilter.merge(rightFilter);
			this.replacement = leftFilter;
		} else {
			andExpression.setLeftExpression(left);
			andExpression.setRightExpression(right);
		}
	}

	@Override
	public void visit(OrExpression orExpression) {
		this.normalizeBinaryExpression(orExpression);
	}

	@Override
	public void visit(XorExpression orExpression) {
		this.normalizeBinaryExpression(orExpression);
		
	}


	@Override
	public void visit(Between between) {
		Expression left = between.getLeftExpression();
		Expression first = between.getBetweenExpressionStart();
		Expression last = between.getBetweenExpressionEnd();

		if (left instanceof Column column) {
			String field = column.getFullyQualifiedName();
			ExpressionList<Expression> list = new ExpressionList<>();
			
			
			list.addExpression(first);
			list.addExpression(last);
			
			this.replacement = new FilterExpression(field, "BETWEEN", list);
		}

	}

	

	@Override
	public void visit(OverlapsCondition overlapsCondition) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void visit(EqualsTo equalsTo) {
		this.normalizeComparison(equalsTo);
		
	}

	@Override
	public void visit(GreaterThan greaterThan) {
		this.normalizeComparison(greaterThan);
		
	}

	@Override
	public void visit(GreaterThanEquals greaterThanEquals) {
		this.normalizeComparison(greaterThanEquals);
		
	}

	@Override
	public void visit(InExpression inExpression) {
		Column column = inExpression.getLeftExpression(Column.class);
		ExpressionList<?> inClause = inExpression.getRightExpression(ParenthesedExpressionList.class);
		
		this.replacement = new FilterExpression(column.getColumnName(), "IN", inClause);
	}

	@Override
	public void visit(FullTextSearch fullTextSearch) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void visit(IsNullExpression isNullExpression) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void visit(IsBooleanExpression isBooleanExpression) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void visit(LikeExpression likeExpression) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void visit(MinorThan minorThan) {
		this.normalizeComparison(minorThan);
		
	}

	@Override
	public void visit(MinorThanEquals minorThanEquals) {
		this.normalizeComparison(minorThanEquals);
		
	}

	@Override
	public void visit(NotEqualsTo notEqualsTo) {
		this.normalizeComparison(notEqualsTo);
		
	}

	@Override
	public void visit(ParenthesedSelect selectBody) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void visit(Column tableColumn) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void visit(CaseExpression caseExpression) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void visit(WhenClause whenClause) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void visit(ExistsExpression existsExpression) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void visit(MemberOfExpression memberOfExpression) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void visit(AnyComparisonExpression anyComparisonExpression) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void visit(Concat concat) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void visit(Matches matches) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void visit(BitwiseAnd bitwiseAnd) {
		this.normalizeBinaryExpression(bitwiseAnd);
		
	}

	@Override
	public void visit(BitwiseOr bitwiseOr) {
		this.normalizeBinaryExpression(bitwiseOr);
		
	}

	@Override
	public void visit(BitwiseXor bitwiseXor) {
		this.normalizeBinaryExpression(bitwiseXor);
		
	}

	@Override
	public void visit(CastExpression cast) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void visit(Modulo modulo) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void visit(AnalyticExpression aexpr) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void visit(ExtractExpression eexpr) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void visit(IntervalExpression iexpr) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void visit(OracleHierarchicalExpression oexpr) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void visit(RegExpMatchOperator rexpr) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void visit(JsonExpression jsonExpr) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void visit(JsonOperator jsonExpr) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void visit(UserVariable var) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void visit(NumericBind bind) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void visit(KeepExpression aexpr) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void visit(MySQLGroupConcat groupConcat) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void visit(ExpressionList<?> expressionList) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void visit(RowConstructor<?> rowConstructor) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void visit(RowGetExpression rowGetExpression) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void visit(OracleHint hint) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void visit(TimeKeyExpression timeKeyExpression) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void visit(DateTimeLiteralExpression literal) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void visit(NotExpression aThis) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void visit(NextValExpression aThis) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void visit(CollateExpression aThis) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void visit(SimilarToExpression aThis) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void visit(ArrayExpression aThis) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void visit(ArrayConstructor aThis) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void visit(VariableAssignment aThis) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void visit(XMLSerializeExpr aThis) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void visit(TimezoneExpression aThis) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void visit(JsonAggregateFunction aThis) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void visit(JsonFunction aThis) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void visit(ConnectByRootOperator aThis) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void visit(OracleNamedFunctionParameter aThis) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void visit(AllColumns allColumns) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void visit(AllTableColumns allTableColumns) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void visit(AllValue allValue) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void visit(IsDistinctExpression isDistinctExpression) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void visit(GeometryDistance geometryDistance) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void visit(Select selectBody) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void visit(TranscodingFunction transcodingFunction) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void visit(TrimFunction trimFunction) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void visit(RangeExpression rangeExpression) {
		// TODO Auto-generated method stub
		
	}

}
