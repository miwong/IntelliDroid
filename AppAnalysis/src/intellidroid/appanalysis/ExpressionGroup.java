package intellidroid.appanalysis;

import java.util.*;
import org.apache.commons.lang3.StringUtils;
import com.ibm.wala.types.*;
import com.ibm.wala.shrikeBT.*;

class ExpressionGroup {
    static public interface Callable<T> {
        public T call(Expression expr);
    }

    private List<Expression> _expressions = new ArrayList<Expression>();

    public ExpressionGroup() {
    }

    public ExpressionGroup(Expression expr) {
        _expressions.add(expr);
    }

    public void add(Expression expr) {
        _expressions.add(expr);
    }

    public void addAll(ExpressionGroup exprGrp) {
        _expressions.addAll(exprGrp.toList());
    }

    public boolean isEmpty() {
        return _expressions.isEmpty();
    }

    public List<Expression> toList() {
        return _expressions;
    }

    public Predicate toPredicate() {
        Predicate pred = null;

        for (Expression expr : _expressions) {
            pred = Predicate.combine(Predicate.Operator.AND, pred, new Predicate(expr));
        }

        return pred;
    }

    public Predicate toNotPredicate() {
        Predicate pred = null;

        for (Expression expr : _expressions) {
            pred = Predicate.combine(Predicate.Operator.OR, pred, new Predicate(Predicate.Operator.NOT, new Predicate(expr)));
        }

        return pred;
    }

    public String toString() {
        return StringUtils.join(_expressions, " | ");
    }

    public boolean evaluate(Callable<Boolean> func) {
        for (Expression expr : _expressions) {
            if (func.call(expr)) {
                return true;
            }
        }

        return false;
    }

    static public ExpressionGroup extract(Callable<Expression> func, ExpressionGroup exprGrp) {
        ExpressionGroup result = new ExpressionGroup();

        if (exprGrp == null) {
            return result;
        }

        for (Expression expr : exprGrp.toList()) {
            Expression newExpr = func.call(expr);
            if (newExpr != null) {
                result.add(newExpr);
            }
        }

        return result;
    }

    static public ExpressionGroup combine(Expression.Operator operator, ExpressionGroup leftExprGrp, Expression rightExpr) {
        if (leftExprGrp == null && rightExpr == null) {
            return null;
        } else if (leftExprGrp == null) {
            return new ExpressionGroup(rightExpr);
        } else if (rightExpr == null) {
            return leftExprGrp;
        }

        ExpressionGroup result = new ExpressionGroup();

        for (Expression leftExpr : leftExprGrp.toList()) {
            result.add(Expression.combine(operator, leftExpr, rightExpr));
        }

        return result;
    }

    static public ExpressionGroup combine(Expression.Operator operator, Expression leftExpr, ExpressionGroup rightExprGrp) {
        if (leftExpr == null && rightExprGrp == null) {
            return null;
        } else if (leftExpr == null) {
            return rightExprGrp;
        } else if (rightExprGrp == null) {
            return new ExpressionGroup(leftExpr);
        }

        ExpressionGroup result = new ExpressionGroup();

        for (Expression rightExpr : rightExprGrp.toList()) {
            result.add(Expression.combine(operator, leftExpr, rightExpr));
        }

        return result;
    }

    static public ExpressionGroup combine(Expression.Operator operator, ExpressionGroup leftExprGrp, ExpressionGroup rightExprGrp) {
        if (leftExprGrp == null && rightExprGrp == null) {
            return null;
        } else if (leftExprGrp == null) {
            return rightExprGrp;
        } else if (rightExprGrp == null) {
            return leftExprGrp;
        }

        ExpressionGroup result = new ExpressionGroup();

        for (Expression leftExpr : leftExprGrp.toList()) {
            for (Expression rightExpr : rightExprGrp.toList()) {
                Expression newExpr = Expression.combine(operator, leftExpr, rightExpr);
                result.add(newExpr);
            }
        }

        return result;
    }

    static public ExpressionGroup combine(IBinaryOpInstruction.IOperator operator, ExpressionGroup leftExprGrp, ExpressionGroup rightExprGrp) {
        if (leftExprGrp == null && rightExprGrp == null) {
            return null;
        } else if (leftExprGrp == null) {
            return rightExprGrp;
        } else if (rightExprGrp == null) {
            return leftExprGrp;
        }

        ExpressionGroup result = new ExpressionGroup();

        for (Expression leftExpr : leftExprGrp.toList()) {
            for (Expression rightExpr : rightExprGrp.toList()) {
                result.add(Expression.combine(operator, leftExpr, rightExpr));
            }
        }

        return result;
    }

    static public ExpressionGroup combine(IConditionalBranchInstruction.IOperator operator, ExpressionGroup leftExprGrp, ExpressionGroup rightExprGrp) {
        if (leftExprGrp == null && rightExprGrp == null) {
            return null;
        } else if (leftExprGrp == null) {
            return rightExprGrp;
        } else if (rightExprGrp == null) {
            return leftExprGrp;
        }

        ExpressionGroup result = new ExpressionGroup();

        for (Expression leftExpr : leftExprGrp.toList()) {
            for (Expression rightExpr : rightExprGrp.toList()) {
                result.add(Expression.combine(operator, leftExpr, rightExpr));
            }
        }

        return result;
    }
}

