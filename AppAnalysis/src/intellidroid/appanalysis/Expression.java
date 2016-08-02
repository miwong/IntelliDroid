package intellidroid.appanalysis;

import org.apache.commons.lang3.builder.HashCodeBuilder;
import com.ibm.wala.types.*;
import com.ibm.wala.shrikeBT.*;

class Expression {
    public enum Operator {
        NONE, 
        ADD, SUB, MUL, DIV, REM,
        GT, GE, LT, LE, EQ, NE,
        AND, OR, XOR,
        SHL, SHR,
        CMP
    }

    public enum Type {
        NONE,
        INT,
        BOOL,
        LONG,
        FLOAT,
        DOUBLE,
        STRING,
        BITVEC
    }

    static private final TypeReference JavaLangAppString = TypeReference.findOrCreate(ClassLoaderReference.Application, "Ljava/lang/String");

    private String _variable;
    private Operator _operator = Operator.NONE;
    private Expression _left = null;
    private Expression _right = null;
    private Type _type = Type.NONE;

    public Expression(String variable, TypeReference type) {
        _variable = variable;
        _type = getTypeFromTypeReference(type);
    }

    public Expression(String variable, Type type) {
        _variable = variable;
        _type = type;
    }

    public Expression(Operator operator, Expression left, Expression right) {
        _operator = operator;
        _left = left;
        _right = right;

        if (isBitwiseOperator(_operator)) {
            setType(Type.BITVEC);
        }
    }

    public Expression(IBinaryOpInstruction.IOperator operator, Expression left, Expression right) {
        if (operator instanceof IBinaryOpInstruction.Operator) {
            _operator = getOperatorForBinaryOp((IBinaryOpInstruction.Operator)operator);
        } else {
            _operator = getOperatorForShiftOp((IShiftInstruction.Operator)operator);
        }

        _left = left;
        _right = right;

        if (isBitwiseOperator(_operator)) {
            setType(Type.BITVEC);
        }
    }

    public Expression(IConditionalBranchInstruction.IOperator operator, Expression left, Expression right) {
        _operator = getOperatorForCondBranchOp(operator);
        _left = left;
        _right = right;

        if (isBitwiseOperator(_operator)) {
            setType(Type.BITVEC);
        }
    }

    public Expression(Expression expr) {
        _operator = expr.getOperator();
        _left = expr.getLeft();
        _right = expr.getRight();
        _variable = expr.getVariable();
        _type = expr.getType();
    }

    public void set(Expression expr) {
        _operator = expr.getOperator();
        _left = expr.getLeft();
        _right = expr.getRight();
        _variable = expr.getVariable();
        _type = expr.getType();
    }

    public void set(String variable, Type type) {
        _variable = variable;
        _type = type;
        _operator = Operator.NONE;
        _left = null;
        _right = null;
    }

    public void set(Operator operator, Expression left, Expression right) {
        _operator = operator;
        _left = left;
        _right = right;
        _variable = null;
        _type = Type.NONE;
    }

    public void setTrue() {
        _variable = "<true>";
        _type = Type.INT;
        _operator = Operator.NONE;
        _left = null;
        _right = null;
    }

    public void setFalse() {
        _variable = "<false>";
        _type = Type.INT;
        _operator = Operator.NONE;
        _left = null;
        _right = null;
    }

    public void setType(Expression.Type type) {
        if (isVariable()) {
            _type = type;
        } else if (isExpression()) {
            getLeft().setType(type);
            getRight().setType(type);
        }
    }

    static Expression getTrue() {
        return new Expression("<true>", TypeReference.Boolean);
    }

    static Expression getFalse() {
        return new Expression("<false>", TypeReference.Boolean);
    }

    public boolean isVariable() {
        return _operator.equals(Operator.NONE);
    }

    public boolean isExpression() {
        return !_operator.equals(Operator.NONE);
    }

    public String getVariable() {
        return _variable;
    }

    public Operator getOperator() {
        return _operator;
    }

    public Type getType() {
        return _type;
    }

    public boolean isTrue() {
        if (isVariable() && getVariable().equals("<true>")) {
            return true;
        }

        return false;
    }

    public boolean isFalse() {
        if (isVariable() && getVariable().equals("<false>")) {
            return true;
        }

        return false;
    }

    static private Operator getOperatorForBinaryOp(IBinaryOpInstruction.Operator operator) {
        switch (operator) {
            case ADD: return Operator.ADD;
            case SUB: return Operator.SUB;
            case MUL: return Operator.MUL;
            case DIV: return Operator.DIV;
            case REM: return Operator.REM;
            case AND: return Operator.AND;
            case OR: return Operator.OR;
            case XOR: return Operator.XOR;
            default: return Operator.NONE;
        }
    }

    static private Operator getOperatorForCondBranchOp(IConditionalBranchInstruction.IOperator operator) {
        switch ((IConditionalBranchInstruction.Operator)operator) {
            case EQ: return Operator.EQ;
            case NE: return Operator.NE;
            case GT: return Operator.GT;
            case GE: return Operator.GE;
            case LT: return Operator.LT;
            case LE: return Operator.LE;
            default: return Operator.NONE;
        }
    }

    static private Operator getOperatorForShiftOp(IShiftInstruction.Operator operator) {
        switch (operator) {
            case SHL: return Operator.SHL;
            case SHR: return Operator.SHR;
            case USHR: return Operator.SHR;
            default: return Operator.NONE;
        }
    }

    public Expression getLeft() {
        return _left;
    }

    public Expression getRight() {
        return _right;
    }

    public boolean equals(Object o) {
        if (!(o instanceof Expression)) {
            return false;
        }

        Expression other = (Expression)o;

        if (isVariable() && other.isVariable()) {
            if (getVariable().equals(other.getVariable())) {
                return true;
            }
        }

        if (isExpression() && other.isExpression()) {
            if (getOperator().equals(other.getOperator()) && 
                getLeft().equals(other.getLeft()) &&
                getRight().equals(other.getRight())) {

                return true;
            }
        }

        return false;
    }

    public int hashCode() {
        return new HashCodeBuilder().
            append(_variable).
            append(_left).
            append(_right).
            toHashCode();
    }

    public boolean dependsOnInput() {
        if (isVariable()) {
            return getVariable().contains("<Input") || getVariable().contains("<ChainedInput");
        }

        if (isExpression()) {
            return getLeft().dependsOnInput() || getRight().dependsOnInput();
        }

        return false;
    }

    public boolean isOppositeOf(Expression expr) {
        if (!isExpression() || !expr.isExpression()) {
            return false;
        }

        if (_left.isEquivalentTo(expr.getLeft()) && _right.isEquivalentTo(expr.getRight())) {
            //if (_operator.equals(Operator.EQ) && !expr.getOperator().equals(Operator.EQ)) {
            if (_operator.equals(Operator.EQ) && Expression.isNotEqualOperator(expr.getOperator())) {
                return true;
            }

            //if (expr.getOperator().equals(Operator.EQ) && !_operator.equals(Operator.EQ)) {
            if (expr.getOperator().equals(Operator.EQ) && Expression.isNotEqualOperator(_operator)) {
                return true;
            }
        } else if (_left.isEquivalentTo(expr.getLeft()) && _left.isVariable() && _left.getVariable().contains("<return>")) {
            if (_operator.equals(Operator.EQ) && expr.getOperator().equals(Operator.EQ)) {
                return true;
            }
        } else if (_right.isEquivalentTo(expr.getRight()) && _right.isVariable() && _right.getVariable().contains("<return>")) {
            if (_operator.equals(Operator.EQ) && expr.getOperator().equals(Operator.EQ)) {
                return true;
            }
        }

        //System.out.println("Not opposite: " + _left.toString() + "; " + expr.getLeft().toString());
        return false;
    }

    public boolean isEquivalentTo(Expression expr) {
        if (this.equals(expr)) {
            return true;
        }

        if (isExpression() && expr.isExpression() && _left.isEquivalentTo(expr.getLeft()) && _right.isEquivalentTo(expr.getRight())) {
            if (_operator.equals(Operator.EQ)) {
                if (expr.getOperator().equals(Operator.LE) || expr.getOperator().equals(Operator.GE)) {
                    return true;
                }
            }

            if (expr.getOperator().equals(Operator.EQ)) {
                if (_operator.equals(Operator.LE) || _operator.equals(Operator.GE)) {
                    return true;
                }
            }

            if (_operator.equals(Operator.NE)) {
                if (expr.getOperator().equals(Operator.LT) || expr.getOperator().equals(Operator.GT)) {
                    return true;
                }
            }

            if (expr.getOperator().equals(Operator.NE)) {
                if (_operator.equals(Operator.LT) || _operator.equals(Operator.GT)) {
                    return true;
                }
            }
        }

        return false;
    }

    public boolean implies(Expression expr) {
        if (this.isEquivalentTo(expr)) {
            if (_operator.equals(Operator.EQ)) {
                switch (expr.getOperator()) {
                    case LE:
                    case GE:
                    case EQ:
                        return true;
                    default:
                        break;
                }
           } else if (_operator.equals(Operator.NE)) {
                switch (expr.getOperator()) {
                    case LT:
                    case GT:
                    case NE:
                        return true;
                    default:
                        break;
                }
            }
        //} else if (this.isExpression() && expr.isExpression()) {
        //    if (_left.isEquivalentTo(expr.getLeft()) && !_right.isEquivalentTo(expr.getRight())) {
        //        if (_operator.equals(Operator.EQ) && expr.getOperator().equals(Operator.NE)) {
        //            return true;
        //        }
        //    }
        }

        return false;
    }

    public boolean isSimpleExpression() {
        if (isExpression()) {
            if (_left.isVariable() && _right.isVariable()) {
                return true;
            }
        //} else if (isTrue() || isFalse()) {
        //    return true;
        }

        return false;
    }

    static boolean isNotEqualOperator(Expression.Operator operator) {
        switch (operator) {
            case NE:
            case GT:
            case LT:
                return true;
            default:
                return false;
        }
    }

    // Check if there is an inverse operator.  If so, return the opposite operator
    static Expression.Operator getOppositeOperator(Expression.Operator operator) {
        switch (operator) {
            case EQ: return Operator.NE;
            case NE: return Operator.EQ;
            case GT: return Operator.LE;
            case GE: return Operator.LT;
            case LT: return Operator.GE;
            case LE: return Operator.GT;
            default: return Operator.NONE;
        }
    }

    private Type getTypeFromTypeReference(TypeReference type) {
        if (type.equals(TypeReference.Int)) {
            return Type.INT;
        } else if (type.equals(TypeReference.Boolean)) {
            //return Type.BOOL;
            return Type.INT;
        } else if (type.equals(TypeReference.Long)) {
            return Type.LONG;
        } else if (type.equals(TypeReference.Float)) {
            return Type.FLOAT;
        } else if (type.equals(TypeReference.Double)) {
            return Type.DOUBLE;
        } else if (type.equals(TypeReference.JavaLangString) || type.equals(JavaLangAppString)) {
            return Type.STRING;
        } else if (type.equals(TypeReference.findOrCreate(ClassLoaderReference.Application, "Ljava/lang/CharSequence"))) {
            return Type.STRING;
        } else {
            return Type.NONE;
        }
    }


    private boolean isBitwiseOperator(Expression.Operator operator) {
        switch (operator) {
            case AND:
            case OR:
            case XOR:
            case CMP:
            case SHL:
            case SHR:
                return true;
            default:
                return false;
        }
    }

    //-------------------------------------------------------------------------

    public static Expression combine(Operator operator, Expression left, Expression right) {
        // Handle the CMP operation in a special case
        if (left != null && left.isExpression() && left.getOperator().equals(Operator.CMP)) {
            Expression oldExpr = left;
            left = oldExpr.getLeft();
            right = oldExpr.getRight();
        }

        if (left == null && right == null) {
            return null;
        } else if (left == null) {
            return right;
        } else if (right == null) {
            return left;
        }

        return new Expression(operator, left, right);
    }

    public static Expression combine(IBinaryOpInstruction.IOperator operator, Expression left, Expression right) {
        Operator op;
        if (operator instanceof IBinaryOpInstruction.Operator) {
            op = getOperatorForBinaryOp((IBinaryOpInstruction.Operator)operator);
        } else {
            op = getOperatorForShiftOp((IShiftInstruction.Operator)operator);
        }
        
        return Expression.combine(op, left, right);
    }

    public static Expression combine(IConditionalBranchInstruction.IOperator operator, Expression left, Expression right) {
        Operator op = getOperatorForCondBranchOp(operator);
        return Expression.combine(op, left, right);
    }

    public static Expression duplicate(Expression expr) {
        if (expr == null) {
            return null;
        }

        if (expr.isVariable()) {
            return new Expression(expr.getVariable(), expr.getType());
        } else if (expr.isExpression()) {
            return new Expression(expr.getOperator(), Expression.duplicate(expr.getLeft()), Expression.duplicate(expr.getRight()));
        }

        return null;
    }

    //-------------------------------------------------------------------------

    public String toString() {
        if (isVariable()) {
            return _variable;
        }

        if (isExpression()) {
            StringBuilder exprString = new StringBuilder();
            exprString.append("(");
            exprString.append(getLeft().toString());
            exprString.append(" ");
            exprString.append(getOperatorString());
            exprString.append(" ");
            exprString.append(getRight().toString());
            exprString.append(")");

            return exprString.toString();
        }

        return "";
    }

    private String getOperatorString() {
        switch (_operator) {
            case ADD: return "+";
            case SUB: return "-";
            case MUL: return "*";
            case DIV: return "/";
            case REM: return "%";
            case GT: return ">";
            case GE: return ">=";
            case LT: return "<";
            case LE: return "<=";
            case EQ: return "==";
            case NE: return "!=";
            case AND: return "&";
            case OR:  return "|";
            case XOR: return "^";
            case CMP: return "cmp";
            case SHL: return "<<";
            case SHR: return ">>";
            default:  return "";
        }
    }
}

