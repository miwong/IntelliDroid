package intellidroid.appanalysis;

import java.util.*;
import org.apache.commons.lang3.math.NumberUtils;
import com.google.gson.JsonObject;

class Z3ConstraintGenerator {
    Predicate _constraint;
    String _z3Constraint;
    String _z3VariableDeclarations;
    Map<Expression, String> _variableMap = new HashMap<Expression, String>();
    Map<String, Integer> _stringMap = new LinkedHashMap<String, Integer>();
    Set<String> _stringVariables = new HashSet<String>();
    int _variableNum = 0;
    int _stringNum = 7000;

    public Z3ConstraintGenerator(Predicate constraint) {
        _constraint = constraint;
        _z3Constraint = generateZ3Constraint(constraint);
        _z3VariableDeclarations = generateZ3VariableDeclarations();
    }

    public String getZ3ConstraintString() {
        return _z3Constraint;
    }

    public JsonObject getVariableJsonObject() {
        JsonObject jsonObject = new JsonObject();

        for (Expression expr : _variableMap.keySet()) {
            String exprVariable = expr.getVariable();

            if (!exprVariable.contains("<return>") &&
                !exprVariable.contains("Pointer<")) {

                jsonObject.addProperty(expr.getVariable(), _variableMap.get(expr));
            }
        }

        return jsonObject;
    }

    public JsonObject getStringJsonObject() {
        JsonObject jsonObject = new JsonObject();

        for (String stringVar : _stringVariables) {
            jsonObject.addProperty(stringVar, "");
        }

        return jsonObject;
    }

    public JsonObject getStringMapJsonObject() {
        JsonObject jsonObject = new JsonObject();

        for (String string : _stringMap.keySet()) {
            jsonObject.addProperty(Integer.toString(_stringMap.get(string)), string);
        }

        return jsonObject;
    }

    public String getZ3ConstraintCode() {
        StringBuilder code = new StringBuilder();
        code.append(_z3VariableDeclarations);
        code.append("\n");
        //code.append("s = Solver()");
        //code.append("\n\n");
        code.append("s.add(");
        code.append(_z3Constraint);
        code.append(")");
        code.append("\n\n");

        //code.append("print s.check()\n");
        //code.append("if s.check():\n");
        //code.append("    print s.model()\n");

        return code.toString();
    }

    private String getZ3Variable(Expression expr) {
        String variable = expr.getVariable();

        if (expr.getType().equals(Expression.Type.STRING) && !expr.dependsOnInput() && 
            !variable.contains("<System") && !variable.contains("<CurrentDate>") && !variable.contains("SharedPreferences<") && 
            !variable.contains("<return>") && !variable.contains("Pointer<")) {

            if (!_stringMap.containsKey(variable)) {
                _stringMap.put(variable, _stringNum);
                _stringNum++;
            }

            return Integer.toString(_stringMap.get(variable));

        } else if (NumberUtils.isNumber(variable)) {
            // Check if this is a numeric constant
            return variable;
        } else if (variable.equals("null")) {
            return "0";
        } else if (variable.equals("0.0")) {
            return variable;
        }

        if (!_variableMap.containsKey(expr)) {
            _variableMap.put(expr, getNewZ3VariableName());

            if (expr.getType().equals(Expression.Type.STRING)) {
                _stringVariables.add(_variableMap.get(expr));
            }
        }

        return _variableMap.get(expr);
    }

    private String getNewZ3VariableName() {
        return "IAAv" + (_variableNum++);
    }

    private String getZ3LogicOperatorString(Predicate.Operator operator) {
        switch (operator) {
            case AND: return "And";
            case OR: return "Or";
            case NOT: return "Not";
            default:  return "";
        }
    }

    private String getZ3OperatorString(Expression.Operator operator) {
        switch (operator) {
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
            case OR: return "|";
            case XOR: return "^";
            case SHL: return "<<";
            case SHR: return ">>";
            default:  return "";
        }
    }

    private String generateZ3Constraint(Predicate constraint) {
        StringBuilder z3Constraint = new StringBuilder();

        if (constraint.isExpression()) {
            z3Constraint.append(generateZ3Expression(constraint.getExpression()));
        } else if (constraint.isUnary()) {
            z3Constraint.append(getZ3LogicOperatorString(constraint.getOperator()));
            z3Constraint.append("(");
            z3Constraint.append(generateZ3Constraint(constraint.getLeft()));
            z3Constraint.append(")");
        } else if (constraint.isBinary()) {
            z3Constraint.append(getZ3LogicOperatorString(constraint.getOperator()));
            z3Constraint.append("(");
            z3Constraint.append(generateZ3Constraint(constraint.getLeft()));
            z3Constraint.append(", ");
            z3Constraint.append(generateZ3Constraint(constraint.getRight()));
            z3Constraint.append(")");
        }

        return z3Constraint.toString();
    }

    private String generateZ3Expression(Expression expr) {
        StringBuilder z3Expression = new StringBuilder();

        if (expr.isVariable()) {
            z3Expression.append(getZ3Variable(expr));

            //if (expr.dependsOnInput()) {
            //    z3Expression.append(getZ3Variable(expr.getVariable()));
            //} else {
            //    z3Expression.append("$");
            //    z3Expression.append(expr.getVariable());
            //    z3Expression.append("$");
            //}
        } else if (expr.isExpression()) {
            z3Expression.append("(");
            z3Expression.append(generateZ3Expression(expr.getLeft()));
            z3Expression.append(" ");
            z3Expression.append(getZ3OperatorString(expr.getOperator()));
            z3Expression.append(" ");
            z3Expression.append(generateZ3Expression(expr.getRight()));
            z3Expression.append(")");
        }

        return z3Expression.toString();
    }

    private String generateZ3VariableDeclarations() {
        StringBuilder declarations = new StringBuilder();

        for (Expression expr : _variableMap.keySet()) {
            String z3Variable = _variableMap.get(expr);
            declarations.append(z3Variable);

            if (expr.getType().equals(Expression.Type.INT) || expr.getType().equals(Expression.Type.LONG) || expr.getType().equals(Expression.Type.STRING)) {
                declarations.append(" = Int(\'");
                declarations.append(z3Variable);
                declarations.append("\')");
            } else if (expr.getType().equals(Expression.Type.BOOL)) {
                declarations.append(" = Bool(\'");
                declarations.append(z3Variable);
                declarations.append("\')");
            } else if (expr.getType().equals(Expression.Type.BITVEC)) {
                declarations.append(" = BitVec(\'");
                declarations.append(z3Variable);
                declarations.append("\',32)");
            } else {
                declarations.append(" = Real(\'");
                declarations.append(z3Variable);
                declarations.append("\')");
            }

            declarations.append("    # ");
            declarations.append(expr.getVariable());
            declarations.append("\n");
        }

        return declarations.toString();
    }
}

