package intellidroid.appanalysis;

import java.util.*;
import org.apache.commons.lang3.mutable.MutableInt;
import org.apache.commons.lang3.math.NumberUtils;

// TODO: create "changed" flag to determine when to stop minimizing (useful if/when
// iterative algorithm implemented)

class ConstraintMinimization {
    static public void minimize(Predicate constraint) {
        if (constraint == null) {
            return;
        }

        minimizePredicate(constraint);
        if (constraint == null) {
            return;
        }

        removeRedundancies(constraint);
        if (constraint == null) {
            return;
        }

        //removeUnusedConstraints(constraint);
        minimizePredicate(constraint);
    }

    // Recursive method to minimize an pred tree.  Return null if nothing was changed.
    static private void minimizePredicate(Predicate pred) {
        if (pred.isExpression()) {
            minimizeExpression(pred.getExpression());
        } else if (pred.isUnary()) {
            minimizeUnaryPredicate(pred);
        } else if (pred.isBinary()) {
            minimizeBinaryPredicate(pred);
        }
    }

    static private void minimizeUnaryPredicate(Predicate pred) {
        minimizePredicate(pred.getLeft());

        if (pred.getOperator().equals(Predicate.Operator.NOT)) {
            if (pred.getLeft().isExpression()) {
                Expression leftExpression = pred.getLeft().getExpression();

                if (leftExpression.isTrue()) {
                    leftExpression.setFalse();
                    pred.set(leftExpression);
                } else if (leftExpression.isFalse()) {
                    leftExpression.setTrue();
                    pred.set(leftExpression);
                } else if (leftExpression.isExpression()) {
                    Expression.Operator oppositeOp = Expression.getOppositeOperator(leftExpression.getOperator());

                    if (!oppositeOp.equals(Expression.Operator.NONE)) {
                        leftExpression.set(oppositeOp, leftExpression.getLeft(), leftExpression.getRight());
                        pred.set(leftExpression);
                    }
                }
            } else if (pred.getLeft().isUnary() && pred.getLeft().getOperator().equals(Predicate.Operator.NOT)) {
                // NOT operators cancel themselves 
                pred.set(pred.getLeft().getLeft());
            }
        }
    }

    static private void minimizeBinaryPredicate(Predicate pred) {
        minimizePredicate(pred.getLeft());
        minimizePredicate(pred.getRight());

        Predicate leftExpr = pred.getLeft();
        Predicate rightExpr = pred.getRight();

        if (leftExpr.equals(rightExpr)) {
            pred.set(leftExpr);
        }

        if (pred.getOperator().equals(Predicate.Operator.AND)) {
            if (leftExpr.isTrue()) {
                pred.set(rightExpr);
            } else if (leftExpr.isFalse()) {
                pred.set(leftExpr);
            }

            if (rightExpr.isTrue()) {
                pred.set(leftExpr);
            } else if (rightExpr.isFalse()) {
                pred.set(rightExpr);
            }
        } else if (pred.getOperator().equals(Predicate.Operator.OR)) {
            if (leftExpr.isTrue()) {
                pred.set(leftExpr);
            } else if (leftExpr.isFalse()) {
                pred.set(rightExpr);
            }

            if (rightExpr.isTrue()) {
                pred.set(rightExpr);
            } else if (rightExpr.isFalse()) {
                pred.set(leftExpr);
            }
        }
    }

    static private void minimizeExpression(Expression clause) {
        if (clause.isExpression()) {
            if (clause.getLeft().equals(clause.getRight())) {
                if (clause.getOperator().equals(Expression.Operator.EQ)) {
                    clause.setTrue();
                } else if (Expression.isNotEqualOperator(clause.getOperator())) {
                    clause.setFalse();
                }

            } else if (clause.getLeft().isVariable() && NumberUtils.isNumber(clause.getLeft().getVariable()) &&
                       clause.getRight().isVariable() && NumberUtils.isNumber(clause.getRight().getVariable())) {

                if (!NumberUtils.createNumber(clause.getLeft().getVariable()).equals(NumberUtils.createNumber(clause.getRight().getVariable()))) {
                    // Constraint are different numbers
                    if (clause.getOperator().equals(Expression.Operator.EQ)) {
                        clause.setFalse();
                    } else if (Expression.isNotEqualOperator(clause.getOperator())) {
                        clause.setTrue();
                    }
                }
            }
        }
    }

    static private void removeRedundancies(Predicate pred) {
        if (pred.isUnary()) {
            removeRedundancies(pred.getLeft());

        } else if (pred.isBinary()) {
            removeRedundancies(pred.getLeft());
            removeRedundancies(pred.getRight());

            if (pred.getOperator().equals(Predicate.Operator.AND)) {
                if (pred.getRight().isExpression() && pred.getRight().getExpression().isSimpleExpression()) {
                    propagateAndConstraint(pred.getLeft(), pred.getRight().getExpression());
                    //removeRedundancies(pred.getLeft());
                    return;

                } else if (pred.getLeft().isExpression() && pred.getLeft().getExpression().isSimpleExpression()) {
                    propagateAndConstraint(pred.getRight(), pred.getLeft().getExpression());
                    //removeRedundancies(pred.getRight());
                    return;
                }
            }
        }
    }

    static private void propagateAndConstraint(Predicate pred, Expression andPred) {
        if (pred.isExpression()) {
            if (pred.getExpression().isOppositeOf(andPred)) {
                pred.set(Expression.getFalse());
                return;
            }

            if (andPred.implies(pred.getExpression())) {
                pred.set(Expression.getTrue());
                return;
            }

        } else if (pred.isUnary()) {
            propagateAndConstraint(pred.getLeft(), andPred);

            if (pred.getLeft().isExpression() && pred.getOperator().equals(Predicate.Operator.NOT)) {
                // isEquivalentTo?  implies?
                if (pred.getLeft().getExpression().equals(andPred)) {
                    pred.set(Expression.getFalse());
                    return;
                } else if (pred.getLeft().getExpression().isOppositeOf(andPred)) {
                    pred.set(Expression.getTrue());
                    return;
                }
            }

        } else if (pred.isBinary()) {
            propagateAndConstraint(pred.getLeft(), andPred);
            propagateAndConstraint(pred.getRight(), andPred);

            if (pred.getOperator().equals(Predicate.Operator.AND)) {
                if (pred.getLeft().isFalse() || pred.getRight().isFalse()) {
                    pred.set(Expression.getFalse());
                } else if (pred.getLeft().isTrue()) {
                    pred.set(pred.getRight());
                } else if (pred.getRight().isTrue()) {
                    pred.set(pred.getLeft());
                }

            } else if (pred.getOperator().equals(Predicate.Operator.OR)) {
                if (pred.getLeft().isTrue() || pred.getRight().isTrue()) {
                    pred.set(Expression.getTrue());
                } else if (pred.getLeft().isFalse()) {
                    pred.set(pred.getRight());
                } else if (pred.getRight().isFalse()) {
                    pred.set(pred.getLeft());
                }
            }
        }
    }
}

