package org.art.db.sql;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.art.db.common.Comparison;
import org.art.db.common.Converter;
import org.art.db.common.Document;
import org.art.db.common.DocumentSet;
import org.art.db.common.Filter;
import org.art.db.common.Key;
import org.art.db.query.QueryManager;
import org.art.db.storage.ColumnInfo;
import org.art.db.common.DBNull;
import org.art.db.common.DBRuntimeException;
import org.art.db.storage.Metadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.sf.jsqlparser.expression.AllValue;
import net.sf.jsqlparser.expression.AnalyticExpression;
import net.sf.jsqlparser.expression.AnyComparisonExpression;
import net.sf.jsqlparser.expression.ArrayConstructor;
import net.sf.jsqlparser.expression.ArrayExpression;
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
import net.sf.jsqlparser.expression.operators.relational.RegExpMatchOperator;
import net.sf.jsqlparser.expression.operators.relational.SimilarToExpression;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.select.AllColumns;
import net.sf.jsqlparser.statement.select.AllTableColumns;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.Select;

public class ExpressionEvaluator implements ExpressionVisitor {
	
	@SuppressWarnings("unused")
	private static final Logger logger = LoggerFactory.getLogger(ExpressionEvaluator.class);
	
	private final String table;
	private final Metadata metadata;
	private List<Comparable<?>> parameters;
	private List<String> selectColumns;
	private long limit;
	private boolean lock;
	private Document document;
	private Object result;
	
	public ExpressionEvaluator(String table) throws Exception {
		this.table = table;
		this.metadata = Metadata.getMetadata(table);
	}
	
	Object evaluate(Expression exp) {
		if (exp instanceof FilterExpression filterExp) {
			this.visit(filterExp);
		} else {
			exp.accept(this);
		}
		
		return result;
	}    
	
	<T> T evaluate(Expression exp, Class<T> clazz) { 
		try {
			return clazz.cast(Converter.convert(this.evaluate(exp), clazz));
		} catch (Exception exc) {
			throw new DBRuntimeException("There was an error converting " + exp + " to " + clazz.getSimpleName());
		}
		
	}
	
	public Object evaluate(Expression exp, Document document, List<Comparable<?>> parameters) {
		this.document = document;
		this.parameters = parameters;
		
		return this.evaluate(exp);
	}
	
	
	<T> T evaluate(Expression exp, Document document, List<Comparable<?>> parameters, Class<T> clazz) {
		try {
			return clazz.cast(Converter.convert(this.evaluate(exp, document, parameters), clazz));
		} catch (Exception exc) {
			throw new DBRuntimeException("There was an error converting " + exp + " to " + clazz.getSimpleName());
		}
	}
	
	
	
	DocumentSet evaluateWhere(Expression exp, List<String> selectColumns, List<Comparable<?>> parameters, long limit, boolean lock) {
		this.selectColumns = selectColumns;
		this.parameters = parameters;
		this.limit = limit;
		this.lock = lock;
		
		
		Object result = this.evaluate(exp);
		
		if (result instanceof DocumentSet set) {
			return set;
		}
		
		throw new DBRuntimeException("The where clause " + exp + " is not valid.");
	}
	
	
	@SuppressWarnings("unchecked")
	public void visit(FilterExpression filterExp) {
		List<Filter> evaluatedFilters = new ArrayList<>();
		
		for (Filter filter : filterExp.getFilters()) {
			try {
				Comparison comp = filter.getComparison();
				String field = filter.getField();
				String prefix = field.contains(".") ? field.substring(0, field.indexOf('.')) : field;
				
				ColumnInfo col = this.metadata.getColumn(prefix);
								
				if (col == null) {
					throw new DBRuntimeException(field + " is not a valid column.");
				}
				
				Expression expression = (Expression)filter.getValue();
				Class<?> type = col.type();
				Object value;
						
				if (expression == null) {
					value = null;
				} else if (expression instanceof NullValue) {
					value = DBNull.NULL;
				} else if (expression instanceof List list) {
					value =  list.stream().map(x-> this.evaluate((Expression)x, type)).toList();
				} else {
					value = type == Document.class ? this.evaluate(expression) : this.evaluate(expression, type);
				}
				
				evaluatedFilters.add(new Filter(comp, field, value));
			} catch (Exception exc) {
				throw new DBRuntimeException(exc.getMessage());
			}
		}
		
		try {
			result = QueryManager.getInstance().select(this.table, this.selectColumns, evaluatedFilters, this.limit, this.lock);
		} catch (DBRuntimeException exc) {
			throw exc;
		} catch (Exception exc) {
			throw new DBRuntimeException("Select failed " + exc.getMessage(), exc);
		}
	}
	
	@Override
	public void visit(BitwiseRightShift aThis) {
		Object left = this.evaluate(aThis.getLeftExpression());
		Object right = this.evaluate(aThis.getRightExpression());
		
		if (left instanceof Number lnum && right instanceof Number rnum) {
			this.result = Long.valueOf(lnum.longValue() >> rnum.intValue());
			return;
		}
		
		throw new UnsupportedSyntaxException("Syntax not supported " + aThis);
		
	}

	@Override
	public void visit(BitwiseLeftShift aThis) {
		Object left = this.evaluate(aThis.getLeftExpression());
		Object right = this.evaluate(aThis.getRightExpression());
		
		if (left instanceof Number lnum && right instanceof Number rnum) {
			this.result = Long.valueOf(lnum.longValue() << rnum.intValue());
			return;
		}
		
		throw new UnsupportedSyntaxException("Syntax not supported "+ aThis);
	}

	@Override
	public void visit(NullValue nullValue) {
		this.result = DBNull.NULL;
		
	}

	@Override
	public void visit(Function function) {
		ExpressionList<?> parameters = function.getParameters();
		List<Object> values = new ArrayList<>();
		
		if (parameters != null) {
			for (Expression exp : parameters) {
				values.add(this.evaluate(exp));
			}
		}
		
		this.result = FunctionEvaluator.evaluate(this.table, this.document, function.getName(), values);
		
	}

	@Override
	public void visit(SignedExpression signedExpression) {
		Object obj = this.evaluate(signedExpression.getExpression());
		
		if (obj instanceof Long lng) {
			this.result = - lng.longValue();
			return;
		} else if (obj instanceof Double dbl) {
			this.result = - dbl.longValue();
			return;
		}
		
		throw new UnsupportedSyntaxException("The - operator is not supported for " + obj.getClass().getSimpleName());
	}

	@Override
	public void visit(JdbcParameter jdbcParameter) {
		int idx = jdbcParameter.getIndex() - 1;
		
		if (idx < this.parameters.size()) {
			this.result = this.parameters.get(idx);
		} else {
			throw new DBRuntimeException("The parameter index is invalid.");
		}
		
	}

	@Override
	public void visit(JdbcNamedParameter jdbcNamedParameter) {
		throw new UnsupportedSyntaxException("Named JDBC parameters are not supported");
		
	}

	@Override
	public void visit(DoubleValue doubleValue) {
		result = doubleValue.getValue();
		
	}

	@Override
	public void visit(LongValue longValue) {
		this.result = longValue.getValue();
		
	}

	@Override
	public void visit(HexValue hexValue) {
		throw new UnsupportedSyntaxException("Hex values are not supported");
		
	}

	@Override
	public void visit(DateValue dateValue) {
		this.result = dateValue.getValue();
		
	}

	@Override
	public void visit(TimeValue timeValue) {
		this.result = timeValue.getValue();
		
	}

	@Override
	public void visit(TimestampValue timestampValue) {
		this.result = timestampValue.getValue();
		
	}

	@Override
	public void visit(Parenthesis parenthesis) {
		result = this.evaluate(parenthesis.getExpression());
		
	}

	@Override
	public void visit(StringValue stringValue) {
		this.result = stringValue.getValue();		
	}

	@Override
	public void visit(Addition addition) {
		Object left = this.evaluate(addition.getLeftExpression());
		Object right = this.evaluate(addition.getRightExpression());
		
		if (left instanceof Number lnum && right instanceof Number rnum) {
			this.result = Long.valueOf(lnum.longValue() + rnum.longValue());
			return;
		} else if (left instanceof String str) {
			this.result = str + right;
			return;
		} else if (right instanceof String str) {
			this.result = left + str;
			return;
		}
		
		throw new UnsupportedSyntaxException("Syntax not supported.");
	}

	@Override
	public void visit(Division division) {
		Object left = this.evaluate(division.getLeftExpression());
		Object right = this.evaluate(division.getRightExpression());
		
		if (left instanceof Number lnum && right instanceof Number rnum) {
			this.result = Long.valueOf(lnum.longValue() / rnum.longValue());
			return;
		}
		
		throw new UnsupportedSyntaxException("Syntax not supported.");
		
	}

	@Override
	public void visit(IntegerDivision division) {
		Object left = this.evaluate(division.getLeftExpression());
		Object right = this.evaluate(division.getRightExpression());
		
		if (left instanceof Number lnum && right instanceof Number rnum) {
			this.result = Long.valueOf(lnum.longValue() / rnum.longValue());
			return;
		}
		
		throw new UnsupportedSyntaxException("Syntax not supported.");
		
	}

	@Override
	public void visit(Multiplication multiplication) {
		Object left = this.evaluate(multiplication.getLeftExpression());
		Object right = this.evaluate(multiplication.getRightExpression());
		
		if (left instanceof Number lnum && right instanceof Number rnum) {
			this.result = Long.valueOf(lnum.longValue() * rnum.longValue());
			return;
		}
		
		
		throw new UnsupportedSyntaxException("Syntax not supported.");
		
	}

	@Override
	public void visit(Subtraction subtraction) {
		Object left = this.evaluate(subtraction.getLeftExpression());
		Object right = this.evaluate(subtraction.getRightExpression());
		
		if (left instanceof Number lnum && right instanceof Number rnum) {
			this.result = Long.valueOf(lnum.longValue() - rnum.longValue());
			return;
		}
		
		throw new UnsupportedSyntaxException("Syntax not supported.");
	}

	@SuppressWarnings("resource")
	@Override
	public void visit(AndExpression andExpression) {
		long saveLimit = this.limit;
		
		try {
			this.limit = Long.MAX_VALUE;
			
			if (andExpression.getLeftExpression() instanceof FilterExpression filter) {
				DocumentSet set = (DocumentSet)this.evaluate(filter);
				DocumentSet newSet = new DocumentSet();

				for (Document doc : set) {
					this.document = doc;

					if (this.evaluate(andExpression.getRightExpression()) == Boolean.TRUE) {
						newSet.add(doc);
					} else {
						QueryManager.getInstance().unlockPrimaryKey(this.table, doc);
					}
				}

				this.result = newSet;
				return;			
			}

			if (andExpression.getRightExpression() instanceof FilterExpression filter) {
				DocumentSet set = (DocumentSet)this.evaluate(filter);
				DocumentSet newSet = new DocumentSet();

				for (Document doc : set) {
					this.document = doc;

					if (this.evaluate(andExpression.getLeftExpression()) == Boolean.TRUE) {
						newSet.add(doc);
					}
				}

				this.result = newSet;
				return;			
			}


			Object left = this.evaluate(andExpression.getLeftExpression());
			Object right = this.evaluate(andExpression.getRightExpression());

			if (left instanceof DocumentSet && right instanceof DocumentSet) {
				throw new UnsupportedSyntaxException("Bad plan for " + andExpression);
			} 

			if (left instanceof DocumentSet) {
				this.result = right.equals(Boolean.TRUE) ? left : new DocumentSet();
				return;
			}

			if (right instanceof DocumentSet) {
				this.result = left.equals(Boolean.TRUE) ? right : new DocumentSet();
				return;
			}

			if (left instanceof Number lnum && right instanceof Number rnum) {
				this.result = Long.valueOf(lnum.longValue() & rnum.longValue());
				return;
			}

			if (left instanceof Boolean lbool && right instanceof Boolean rbool) {
				this.result = lbool & rbool;
				return;
			}

			throw new UnsupportedSyntaxException("The AND operator is not supported for these types");
		} finally {
			this.limit = saveLimit;
		}

	}

	
	@Override
	public void visit(OrExpression orExpression) {
		long saveLimit = this.limit;

		try {
			this.limit = Long.MAX_VALUE;

			Object left = this.evaluate(orExpression.getLeftExpression());
			Object right = this.evaluate(orExpression.getRightExpression());

			// If left and right are document sets then concatenate the sets

			if (left instanceof DocumentSet lset && right instanceof DocumentSet rset) {
				Set<Key> leftKeys = new HashSet<>();

				for (Document doc : rset) {
					leftKeys.add(SQLUtils.buildPrimaryKey(doc, this.metadata));
				}

				for (Document doc : rset) {
					if (!leftKeys.contains(SQLUtils.buildPrimaryKey(doc, this.metadata))) {
						lset.add(doc);
					}
				}

				this.result = left;
				return;
			} 

			if (left instanceof DocumentSet) {
				this.result = left;
				return;
			} 

			if (right instanceof DocumentSet) {
				this.result = right;
				return;
			} 

			if (left instanceof Number lnum && right instanceof Number rnum) {
				this.result = Long.valueOf(lnum.longValue() | rnum.longValue());
				return;
			}

			if (left instanceof Boolean lbool && right instanceof Boolean rbool) {
				this.result = lbool || rbool;
				return;
			}

			throw new UnsupportedSyntaxException("The OR operator is not supported for these types");
		} finally {
			this.limit = saveLimit;
		}
	}

	@Override
	public void visit(XorExpression orExpression) {
		Object left = this.evaluate(orExpression.getLeftExpression());
		Object right = this.evaluate(orExpression.getRightExpression());
		
		if (left instanceof Number lnum && right instanceof Number rnum) {
			this.result = Long.valueOf(lnum.longValue() ^ rnum.longValue());
			return;
		}
		
		if (left instanceof Boolean lbool && right instanceof Boolean rbool) {
			this.result = lbool ^ rbool;
			return;
		}
		
		throw new UnsupportedSyntaxException("The XOR operator is not supported for these types");
	}

	@Override
	public void visit(Between between) {
		throw new UnsupportedSyntaxException("The between operator is not supported");
		
	}

	@Override
	public void visit(OverlapsCondition overlapsCondition) {
		throw new UnsupportedSyntaxException("The between overlaps is not supported");
		
	}

	@Override
	public void visit(EqualsTo equalsTo) {
		Object left = this.evaluate(equalsTo.getLeftExpression());
		Object right = this.evaluate(equalsTo.getRightExpression());
		
		this.result = Objects.equals(left, right);
		return;
		
	}

	@Override
	public void visit(GreaterThan greaterThan) {
		Object left = this.evaluate(greaterThan.getLeftExpression());
		Object right = this.evaluate(greaterThan.getRightExpression());
		
		this.result = Comparison.compare(left, right);
			
	}

	@Override
	public void visit(GreaterThanEquals greaterThanEquals) {
		Object left = this.evaluate(greaterThanEquals.getLeftExpression());
		Object right = this.evaluate(greaterThanEquals.getRightExpression());
		
		this.result = Comparison.compare(left, right) > 0;
		
	}

	@Override
	public void visit(InExpression inExpression) {
		throw new UnsupportedSyntaxException("The IN operator is not supported");
		
	}

	@Override
	public void visit(FullTextSearch fullTextSearch) {
		ExpressionList<Column> matchColumns = fullTextSearch.getMatchColumns();
		
		if (matchColumns.size() == 1) {
			throw new UnsupportedSyntaxException("Fulltext searches are only supported on columns with fulltext indidex");
		} else {
			throw new UnsupportedSyntaxException("Multicolumn full text searches are not supported");
		}
		
	}

	@Override
	public void visit(IsNullExpression isNullExpression) {
		Object value = this.evaluate(isNullExpression.getLeftExpression());
		boolean isNull = value == null || value == DBNull.NULL;
		
		this.result =  isNullExpression.isNot() ? !isNull : isNull;
		
	}

	@Override
	public void visit(IsBooleanExpression isBooleanExpression) {
		throw new UnsupportedSyntaxException("Syntax not supported.");
		
	}

	@Override
	public void visit(LikeExpression likeExpression) {
		throw new UnsupportedSyntaxException("Syntax not supported.");
		
	}

	@Override
	public void visit(MinorThan minorThan) {
		Object left = this.evaluate(minorThan.getLeftExpression());
		Object right = this.evaluate(minorThan.getRightExpression());
		
		this.result = Comparison.compare(left, right) < 0;
		
	}

	@Override
	public void visit(MinorThanEquals minorThanEquals) {
		Object left = this.evaluate(minorThanEquals.getLeftExpression());
		Object right = this.evaluate(minorThanEquals.getRightExpression());
		
		this.result = Comparison.compare(left, right) <= 0;
		
	}

	@Override
	public void visit(NotEqualsTo notEqualsTo) {
		Object left = this.evaluate(notEqualsTo.getLeftExpression());
		Object right = this.evaluate(notEqualsTo.getRightExpression());
		
		this.result = !Objects.equals(left, right);
		
	}

	@Override
	public void visit(ParenthesedSelect selectBody) {
		throw new UnsupportedSyntaxException("Embedded selects are not supported");
		
	}

	@Override
	public void visit(Column tableColumn) {
		String name = tableColumn.getColumnName();
		
		if (name.contains(".")) {
			name = name.substring(0, name.indexOf('.'));
		}
		
		if (!this.metadata.getColumns().containsKey(name)) {
			throw new DBRuntimeException(name + " is not a valid column name");
		}
		
		if (this.document != null) {
			this.result = this.document.get(tableColumn.getColumnName());
			return;
		}
		
		throw new UnsupportedSyntaxException(name + " is not a valid column name.");
		
	}

	@Override
	public void visit(CaseExpression caseExpression) {
		throw new UnsupportedSyntaxException("Case expressions are not supported");
		
	}

	@Override
	public void visit(WhenClause whenClause) {
		throw new UnsupportedSyntaxException("When clauses are not supported");
		
	}

	@Override
	public void visit(ExistsExpression existsExpression) {
		throw new UnsupportedSyntaxException("Exist clauses are not supported");
		
	}

	@Override
	public void visit(MemberOfExpression memberOfExpression) {
		throw new UnsupportedSyntaxException("Member of is not supported");
		
	}

	@Override
	public void visit(AnyComparisonExpression anyComparisonExpression) {
		throw new UnsupportedSyntaxException("Any clauses are not supported");
		
	}

	@Override
	public void visit(Concat concat) {
		Object left = this.evaluate(concat.getLeftExpression());
		Object right = this.evaluate(concat.getRightExpression());
		
		if (left instanceof String lstr && right instanceof String rstr) {
			this.result = lstr + rstr;
			return;
		}
		
		throw new UnsupportedSyntaxException("Syntax not supported.");
	}

	@Override
	public void visit(Matches matches) {
		throw new UnsupportedSyntaxException("Matches operators are not supported");
		
	}

	@Override
	public void visit(BitwiseAnd bitwiseAnd) {
		Object left = this.evaluate(bitwiseAnd.getLeftExpression());
		Object right = this.evaluate(bitwiseAnd.getRightExpression());
		
		if (left instanceof Number lnum && right instanceof Number rnum) {
			this.result = Long.valueOf(lnum.longValue() &rnum.longValue());
			return;
		}
		
		throw new UnsupportedSyntaxException("Bitwise OR is not supported for these data types");
	}

	@Override
	public void visit(BitwiseOr bitwiseOr) {
		Object left = this.evaluate(bitwiseOr.getLeftExpression());
		Object right = this.evaluate(bitwiseOr.getRightExpression());
		
		if (left instanceof Number lnum && right instanceof Number rnum) {
			this.result = Long.valueOf(lnum.longValue() | rnum.longValue());
			return;
		}
		
		throw new UnsupportedSyntaxException("Bitwise OR is not supported for these data types");
		
	}

	@Override
	public void visit(BitwiseXor bitwiseXor) {
		Object left = this.evaluate(bitwiseXor.getLeftExpression());
		Object right = this.evaluate(bitwiseXor.getRightExpression());
		
		if (left instanceof Number lnum && right instanceof Number rnum) {
			this.result = Long.valueOf(lnum.longValue() ^ rnum.longValue());
			return;
		}
		
		throw new UnsupportedSyntaxException("Bitwise XOR is not supported for these data types");
		
	}

	@Override
	public void visit(CastExpression cast) {
		throw new UnsupportedSyntaxException("Casts are not supported");
		
	}

	@Override
	public void visit(Modulo modulo) {
		Object left = this.evaluate(modulo.getLeftExpression());
		Object right = this.evaluate(modulo.getRightExpression());
		
		if (left instanceof Number lnum && right instanceof Number rnum) {
			this.result = Long.valueOf(lnum.longValue() % rnum.longValue());
			return;
		}
		
		throw new UnsupportedSyntaxException("Modulo is not supported for these data types");
		
	}

	@Override
	public void visit(AnalyticExpression aexpr) {
		throw new UnsupportedSyntaxException("Syntax not supported.");
		
	}

	@Override
	public void visit(ExtractExpression eexpr) {
		throw new UnsupportedSyntaxException("Syntax not supported.");
		
	}

	@Override
	public void visit(IntervalExpression iexpr) {
		throw new UnsupportedSyntaxException("Syntax not supported.");
		
	}

	@Override
	public void visit(OracleHierarchicalExpression oexpr) {
		throw new UnsupportedSyntaxException("Syntax not supported.");
		
	}

	@Override
	public void visit(RegExpMatchOperator rexpr) {
		throw new UnsupportedSyntaxException("Syntax not supported.");
		
	}

	@Override
	public void visit(JsonExpression jsonExpr) {
		throw new UnsupportedSyntaxException("Syntax not supported.");
		
	}

	@Override
	public void visit(JsonOperator jsonExpr) {
		throw new UnsupportedSyntaxException("Syntax not supported.");
		
	}

	@Override
	public void visit(UserVariable var) {
		throw new UnsupportedSyntaxException("Syntax not supported.");
		
	}

	@Override
	public void visit(NumericBind bind) {
		throw new UnsupportedSyntaxException("Syntax not supported.");
		
	}

	@Override
	public void visit(KeepExpression aexpr) {
		throw new UnsupportedSyntaxException("Syntax not supported.");
		
	}

	@Override
	public void visit(MySQLGroupConcat groupConcat) {
		throw new UnsupportedSyntaxException("Syntax not supported.");
		
	}

	@Override
	public void visit(ExpressionList<?> expressionList) {
		throw new UnsupportedSyntaxException("Syntax not supported.");
		
	}

	@Override
	public void visit(RowConstructor<?> rowConstructor) {
		throw new UnsupportedSyntaxException("Syntax not supported.");
		
	}

	@Override
	public void visit(RowGetExpression rowGetExpression) {
		throw new UnsupportedSyntaxException("Syntax not supported.");
		
	}

	@Override
	public void visit(OracleHint hint) {
		throw new UnsupportedSyntaxException("Syntax not supported.");
		
	}

	@Override
	public void visit(TimeKeyExpression timeKeyExpression) {
		throw new UnsupportedSyntaxException("Syntax not supported.");
		
	}

	@Override
	public void visit(DateTimeLiteralExpression literal) {
		throw new UnsupportedSyntaxException("Syntax not supported.");
		
	}

	@Override
	public void visit(NotExpression aThis) {
		throw new UnsupportedSyntaxException("Syntax not supported.");
		
	}

	@Override
	public void visit(NextValExpression aThis) {
		throw new UnsupportedSyntaxException("Syntax not supported.");
		
	}

	@Override
	public void visit(CollateExpression aThis) {
		throw new UnsupportedSyntaxException("Syntax not supported.");
		
	}

	@Override
	public void visit(SimilarToExpression aThis) {
		throw new UnsupportedSyntaxException("Syntax not supported.");
		
	}

	@Override
	public void visit(ArrayExpression aThis) {
		throw new UnsupportedSyntaxException("Syntax not supported.");
		
	}

	@Override
	public void visit(ArrayConstructor aThis) {
		throw new UnsupportedSyntaxException("Syntax not supported.");
		
	}

	@Override
	public void visit(VariableAssignment aThis) {
		throw new UnsupportedSyntaxException("Syntax not supported.");
		
	}

	@Override
	public void visit(XMLSerializeExpr aThis) {
		throw new UnsupportedSyntaxException("Syntax not supported.");
		
	}

	@Override
	public void visit(TimezoneExpression aThis) {
		throw new UnsupportedSyntaxException("Syntax not supported.");
		
	}

	@Override
	public void visit(JsonAggregateFunction aThis) {
		throw new UnsupportedSyntaxException("Syntax not supported.");
		
	}

	@Override
	public void visit(JsonFunction aThis) {
		throw new UnsupportedSyntaxException("Syntax not supported.");
		
	}

	@Override
	public void visit(ConnectByRootOperator aThis) {
		throw new UnsupportedSyntaxException("Syntax not supported.");
		
	}

	@Override
	public void visit(OracleNamedFunctionParameter aThis) {
		throw new UnsupportedSyntaxException("Syntax not supported.");
		
	}

	@Override
	public void visit(AllColumns allColumns) {
		throw new UnsupportedSyntaxException("Syntax not supported.");
		
	}

	@Override
	public void visit(AllTableColumns allTableColumns) {
		throw new UnsupportedSyntaxException("Syntax not supported.");
		
	}

	@Override
	public void visit(AllValue allValue) {
		throw new UnsupportedSyntaxException("Syntax not supported.");
		
	}

	@Override
	public void visit(IsDistinctExpression isDistinctExpression) {
		throw new UnsupportedSyntaxException("Syntax not supported.");
		
	}

	@Override
	public void visit(GeometryDistance geometryDistance) {
		throw new UnsupportedSyntaxException("Syntax not supported.");
		
	}

	@Override
	public void visit(Select selectBody) {
		throw new UnsupportedSyntaxException("Syntax not supported.");
		
	}

	@Override
	public void visit(TranscodingFunction transcodingFunction) {
		throw new UnsupportedSyntaxException("Syntax not supported.");
		
	}

	@Override
	public void visit(TrimFunction trimFunction) {
		throw new UnsupportedSyntaxException("Syntax not supported.");
		
	}

	@Override
	public void visit(RangeExpression rangeExpression) {
		throw new UnsupportedSyntaxException("Syntax not supported.");
		
	}

}
