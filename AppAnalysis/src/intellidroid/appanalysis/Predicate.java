package intellidroid.appanalysis;

import com.ibm.wala.types.TypeReference;
import java.lang.NullPointerException;

class Predicate {
    public enum Operator {
        NONE,
        AND,
        OR,
        NOT
    }

    private Expression _expr = null;
    private Operator _operator = Operator.NONE;
    private Predicate _left = null;
    private Predicate _right = null;

    public Predicate(Predicate pred) {
        _expr = pred.getExpression();
        _operator = pred.getOperator();
        _left = pred.getLeft();
        _right = pred.getRight();
    }

    public Predicate(Operator unaryOperator, Predicate left) {
        _operator = unaryOperator;
        _left = left;
    }

    public Predicate(Operator binaryOperator, Predicate left, Predicate right) {
        _operator = binaryOperator;
        _left = left;
        _right = right;
    }

    public Predicate(Expression expr) {
        _expr = expr;
    }

    public Predicate(String variable, TypeReference type) {
        _expr = new Expression(variable, type);
    }

    public boolean isVariable() {
        return this.isExpression() && _expr.isVariable();
    }

    public String getVariable() {
        return _expr.getVariable();
    }

    public static Predicate combine(Operator binaryOperator, Predicate left, Predicate right) {
        if (left == null && right == null) {
            return null;
        } else if (left == null) {
            return right;
        } else if (right == null) {
            return left;
        }

        return new Predicate(binaryOperator, left, right);
    }

    public void set(Expression expr) {
        _expr = expr;
        _operator = Operator.NONE;
        _left = null;
        _right = null;
    }

    public void set(Operator unaryOperator, Predicate left) {
        _operator = unaryOperator;
        _left = left;
        _right = null;
        _expr = null;
    }

    public void set(Operator binaryOperator, Predicate left, Predicate right) {
        _operator = binaryOperator;
        _left = left;
        _right = right;
        _expr = null;
    }

    public void set(Predicate pred) {
        _operator = pred.getOperator();
        _left = pred.getLeft();
        _right = pred.getRight();
        _expr = pred.getExpression();;
    }

    static public Predicate getTrue() {
        return new Predicate(Expression.getTrue());
    }

    static public Predicate getFalse() {
        return new Predicate(Expression.getFalse());
    }

    public boolean equals(Object obj) {
        if (obj == null) {
            return false;
        }

        if (!(obj instanceof Predicate)) {
            return false;
        }

        Predicate other = (Predicate)obj;

        if (this.isUnary() && other.isUnary()) {
            if (this.getOperator().equals(other.getOperator()) &&
                this.getLeft().equals(other.getLeft())) {
                return true;
            } else {
                return false;
            }
        }

        if (this.isBinary() && other.isBinary()) {
            if (this.getOperator().equals(other.getOperator()) &&
                this.getLeft().equals(other.getLeft()) &&
                this.getRight().equals(other.getRight())) {
                return true;
            } else {
                return false;
            }
        }

        if (this.isExpression() && other.isExpression()) {
            if (this.getExpression().equals(other.getExpression())) {
                return true;
            } else {
                return false;
            }
        }

        return false;
    }

    public boolean contains(Predicate other) {
        if (this.equals(other)) {
            return true;
        }

        if (this.isBinary()) {
            if (this.getLeft().contains(other)) {
                return true;
            } else if (this.getRight().contains(other)) {
                return true;
            }
            //return (this.getLeft().contains(other) || this.getRight().contains(other));
        }

        return false;
    }

    public boolean isUnary() {
        return (_left != null) && (_right == null);
    }

    public boolean isBinary() {
        return (_left != null) && (_right != null);
    }

    public boolean isExpression() {
        return (_left == null) && (_right == null);
    }

    public Operator getOperator() {
        return _operator;
    }

    public boolean isTrue() {
        if (isExpression() && getExpression().isTrue()) {
            return true;
        }

        return false;
    }

    public boolean isFalse() {
        if (isExpression() && getExpression().isFalse()) {
            return true;
        }

        return false;
    }

    private String getOperatorString() {
        switch (_operator) {
            case AND: return "and";
            case OR:  return "or";
            case NOT: return "not";
            default:  return "";
        }
    }

    public Expression getExpression() {
        return _expr;
    }

    public Predicate getLeft() {
        return _left;
    }

    public Predicate getRight() {
        return _right;
    }
    
    public boolean isOppositeOf(Predicate other) {
        if (this.isExpression() && other.isExpression()) {
            if (this.getExpression().isOppositeOf(other.getExpression())) {
                return true;
            }
        }

        if (this.isExpression() && other.isUnary() && other.getOperator().equals(Operator.NOT)) {
            if (this.getExpression().equals(other.getLeft())) {
                return true;
            }
        }

        if (this.isUnary() && this.getOperator().equals(Operator.NOT) && other.isExpression()) {
            if (this.getLeft().equals(other.getExpression())) {
                return true;
            }
        }

        if (this.isBinary() && other.isBinary()) {
            if (this.getLeft().equals(other.getLeft())) {
                return this.getRight().isOppositeOf(other.getRight());
            }

            if (this.getRight().equals(other.getRight())) {
                return this.getLeft().isOppositeOf(other.getLeft());
            }
        }

        return false;
    }

    public boolean dependsOnInput() {
        if (isExpression()) {
            return getExpression().dependsOnInput();
        }

        if (isUnary()) {
            return getLeft().dependsOnInput();
        }

        if (isBinary()) {
            return getLeft().dependsOnInput() || getRight().dependsOnInput();
        }

        return false;
    }

    public static Predicate duplicate(Predicate pred) {
        if (pred != null) {
            if (pred.isExpression()) {
                return new Predicate(Expression.duplicate(pred.getExpression()));
            } else if (pred.isUnary()) {
                return new Predicate(pred.getOperator(), Predicate.duplicate(pred.getLeft()));
            } else if (pred.isBinary()) {
                return new Predicate(pred.getOperator(), Predicate.duplicate(pred.getLeft()), Predicate.duplicate(pred.getRight()));
            }
        }

        return null;
    }

    // ------------------------------------------------------------------------

    public void print() {
        print(0);
    }

    public void print(int indent) {
        if (isExpression()) {
            StringBuilder predString = new StringBuilder();

            for (int i = 0; i < indent; i++) {
                predString.append("    ");
            }

            predString.append(getExpression().toString());
            System.out.println(predString.toString());

        } else if (isUnary()) {
            StringBuilder predString = new StringBuilder();

            for (int i = 0; i < indent; i++) {
                predString.append("    ");
            }

            predString.append(getOperatorString());
            System.out.println(predString.toString());

            getLeft().print(indent + 1);

        } else if (isBinary()) {
            if (getLeft().isBinary() && getLeft().getOperator().equals(getOperator())) {
                getLeft().print(indent);
            } else {
                getLeft().print(indent + 1);
            }

            StringBuilder predString = new StringBuilder();
            for (int i = 0; i < indent; i++) {
                predString.append("    ");
            }
            predString.append(getOperatorString());
            System.out.println(predString.toString());

            if (getRight().isBinary() && getRight().getOperator().equals(getOperator())) {
                getRight().print(indent );
            } else {
                getRight().print(indent + 1);
            }
        }
    }

    public String toString() {
        StringBuilder constraints = new StringBuilder();

        if (isExpression()) {
            constraints.append("(");
            constraints.append(getExpression().toString());
            constraints.append(")");

        } else if (isUnary()) {
            constraints.append(getOperatorString());

            if (getLeft().isExpression()) {
                constraints.append(getLeft().toString());
            } else {
                constraints.append("(");
                constraints.append(getLeft().toString());
                constraints.append(")");
            }

        } else if (isBinary()) {
            if (getLeft().isExpression() || 
                (getLeft().isBinary() && getLeft().getOperator().equals(getOperator()))) {

                constraints.append(getLeft().toString());
            } else {
                constraints.append("(");
                constraints.append(getLeft().toString());
                constraints.append(")");
            }

            constraints.append(getOperatorString());

            if (getRight().isExpression() ||
                (getRight().isBinary() && getRight().getOperator().equals(getOperator()))) {

                constraints.append(getRight().toString());
            } else {
                constraints.append("(");
                constraints.append(getRight().toString());
                constraints.append(")");
            }
        }

        return constraints.toString();
    }
}

