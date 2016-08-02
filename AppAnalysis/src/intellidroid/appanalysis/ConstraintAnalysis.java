package intellidroid.appanalysis;

import com.ibm.wala.classLoader.*;
import com.ibm.wala.types.*;
import com.ibm.wala.ipa.cha.*;

import com.ibm.wala.ssa.*;

import com.ibm.wala.ipa.callgraph.*;
import com.ibm.wala.ipa.callgraph.impl.*;
import com.ibm.wala.ipa.callgraph.propagation.*;

import com.ibm.wala.cfg.Util;
import com.ibm.wala.util.graph.dominators.*;
import com.ibm.wala.shrikeBT.IBinaryOpInstruction;

import com.ibm.wala.util.collections.*;
import com.ibm.wala.util.graph.*;
import com.ibm.wala.util.graph.traverse.*;
import com.ibm.wala.util.graph.impl.GraphInverter;
import com.ibm.wala.util.intset.*;
import com.ibm.wala.util.strings.*;

import java.util.*;

class ConstraintAnalysis {
    private static final boolean DEBUG = false;

    static private final Map<String, String> _essentialMethods = new HashMap<String, String>();

    static {
        _essentialMethods.put("android.telephony.gsm.SmsMessage.createFromPdu([B)Landroid/telephony/gsm/SmsMessage;", "<SmsMessage>");
        _essentialMethods.put("android.telephony.SmsMessage.createFromPdu([B)Landroid/telephony/SmsMessage;", "<SmsMessage>");
        _essentialMethods.put("java.lang.System.currentTimeMillis()J", "System.currentTimeMillis()");
        _essentialMethods.put("java.util.Date.<init>()V", "<CurrentDate>");
        _essentialMethods.put("java.net.URLConnection.getInputStream()Ljava/io/InputStream;", "<HttpInput>");
        _essentialMethods.put("android.telephony.TelephonyManager.getDeviceId()Ljava/lang/String;", "<DeviceID>");
        _essentialMethods.put("android.telephony.TelephonyManager.getSubscriberId()Ljava/lang/String;", "<SubscriberID>");
        _essentialMethods.put("android.telephony.TelephonyManager.getSimSerialNumber()Ljava/lang/String;", "<SimSerialNum>");
    }

    private CallPath _callPath;
    private IClassHierarchy _cha;

    private List<Predicate> _pathNodeConstraints = new ArrayList<Predicate>();
    private Predicate _constraints = null;
    private Map<String, PointerKey> _nameToPointerMap = new HashMap<String, PointerKey>();
    private Map<PointerKey, String> _pointerToNameMap = new HashMap<PointerKey, String>();
    private Map<PointerKey, ExpressionGroup> _storeDataMap = new HashMap<PointerKey, ExpressionGroup>();
    private Map<PointerKey, Predicate> _heapDependencies = new HashMap<PointerKey, Predicate>();
    private Map<String, Expression> _sharedPrefDependencies = new HashMap<String, Expression>();

    private List<Expression> _targetParameters = new ArrayList<Expression>();

    private IClass _runnableClass;

    public ConstraintAnalysis(CallPath callPath) {
        _callPath = callPath;
        _cha = callPath.getCallGraph().getClassHierarchy();
        _runnableClass = _cha.lookupClass(TypeReference.findOrCreate(ClassLoaderReference.Extension, "Ljava/lang/Runnable"));
        generateConstraintsAndDataPropagation();
    }

    public Predicate getConstraints() {
        return _constraints;
    }

    public Map<PointerKey, Predicate> getHeapDependencies() {
        return _heapDependencies;
    }

    public Map<String, Expression> getSharedPrefDependencies() {
        return _sharedPrefDependencies;
    }

    public ExpressionGroup getDataForPointerKey(PointerKey pKey) {
        if (_storeDataMap.containsKey(pKey)) {
            return _storeDataMap.get(pKey);
        }

        return null;
    }

    public String getPointerKeyName(PointerKey pKey) {
        if (_pointerToNameMap.containsKey(pKey)) {
            return _pointerToNameMap.get(pKey);
        }

        return null;
    }

    private void generateConstraintsAndDataPropagation() {
        List<CGNode> path = _callPath.getPath();
        CallGraph cg = _callPath.getCallGraph();

        CGNode triggerNode = path.get(0);
        Map<Integer, ExpressionGroup> triggerDataMap = new HashMap<Integer, ExpressionGroup>();

        for (int i = 1; i < triggerNode.getIR().getNumberOfParameters(); i++) {
            triggerDataMap.put(triggerNode.getIR().getParameter(i), new ExpressionGroup(new Expression("<Input" + i + ">", triggerNode.getIR().getParameterType(i))));
        }

        Map<Integer, ExpressionGroup> nextParameterMap = triggerDataMap;

        for (int i = 0; i < path.size(); i++) {
            nextParameterMap = processPathNode(i, nextParameterMap);
        }

        for (Predicate pathNodeConstraint : _pathNodeConstraints) {
            _constraints = Predicate.combine(Predicate.Operator.AND, _constraints, pathNodeConstraint);
        }

        // Remove pathNodeConstraints, in case memory is needed
        _pathNodeConstraints.clear();
        _pathNodeConstraints = null;

        // Make copy of constraint before minimizing them and modifying their contents
        _constraints = Predicate.duplicate(_constraints);

        if (_constraints != null) {
            processInterEventDependencies();
        }

        processTargetParametersConstraints();
    }

    private void processInterEventDependencies() {
        processHeapConstraints();
        processSharedPrefDependencies();
    }

    private void processTargetParametersConstraints() {
        for (Expression paramExpr : _targetParameters) {
            if (paramExpr == null) {
                continue;
            }

            List<PointerKey> paramHeapDependencies = exprContainsHeapDependencies(paramExpr);

            if (paramHeapDependencies != null) {
                for (PointerKey pKey : paramHeapDependencies) {
                    if (!_heapDependencies.keySet().contains(pKey)) {
                        _heapDependencies.put(pKey, Predicate.getTrue());
                    }
                }
            }

            String sharedPrefDependency = exprContainsSharedPrefDependencies(paramExpr);
            if (sharedPrefDependency != null) {
                if (!_sharedPrefDependencies.keySet().contains(sharedPrefDependency)) {
                    _sharedPrefDependencies.put(sharedPrefDependency, Expression.getTrue());
                }
            }
        }
    }

    // ========================================================================

    private void processHeapConstraints() {
        List<PointerKey> heapDependencies = constraintContainsHeapDependencies(_constraints);
        
        for (PointerKey pKey : heapDependencies) {
            if (!_heapDependencies.keySet().contains(pKey)) {
                modifyConstraintVariablesForEventChain(_constraints);
                _heapDependencies.put(pKey, _constraints);
            }
        }
    }

    private List<PointerKey> constraintContainsHeapDependencies(Predicate constraint) {
        if (constraint.isExpression()) {
            return exprContainsHeapDependencies(constraint.getExpression());

        } else if (constraint.isUnary()) {
            return constraintContainsHeapDependencies(constraint.getLeft());

        } else if (constraint.isBinary()) {
            List<PointerKey> leftResult = constraintContainsHeapDependencies(constraint.getLeft());
            List<PointerKey> rightResult = constraintContainsHeapDependencies(constraint.getRight());

            if (!leftResult.isEmpty() && rightResult.isEmpty() && constraint.getOperator().equals(Expression.Operator.OR)) {
                for (PointerKey pKey : leftResult) {
                    modifyConstraintVariablesForEventChain(constraint.getLeft());
                    _heapDependencies.put(pKey, constraint.getLeft());
                }

                return new ArrayList<PointerKey>();

            } else if (leftResult.isEmpty() && !rightResult.isEmpty() && constraint.getOperator().equals(Expression.Operator.OR)) {
                for (PointerKey pKey : rightResult) {
                    modifyConstraintVariablesForEventChain(constraint.getRight());
                    _heapDependencies.put(pKey, constraint.getRight());
                }

                return new ArrayList<PointerKey>();

            } else {
                List<PointerKey> result = new ArrayList<PointerKey>();
                result.addAll(leftResult);
                result.addAll(rightResult);

                return result;
            }
        }

        return null;
    }

    private List<PointerKey> exprContainsHeapDependencies(Expression expr) {
        List<PointerKey> result = new ArrayList<PointerKey>();

        if (expr.isVariable()) {
            if (expr.getVariable().contains("Pointer<")) {
                result.add(_nameToPointerMap.get(expr.getVariable()));
            }

            return result;

        } else if (expr.isExpression()) {
            List<PointerKey> leftResult = exprContainsHeapDependencies(expr.getLeft());
            List<PointerKey> rightResult = exprContainsHeapDependencies(expr.getRight());

            if (leftResult == null || rightResult == null) {
                return result;
            }

            result.addAll(leftResult);
            result.addAll(rightResult);

            return result;
        }

        return null;
    }

    private void modifyConstraintVariablesForEventChain(Predicate constraint) {
        if (constraint.isExpression()) {
            modifyConstraintVariablesForEventChain(constraint.getExpression());

        } else if (constraint.isUnary()) {
            modifyConstraintVariablesForEventChain(constraint.getLeft());

        } else if (constraint.isBinary()) {
            modifyConstraintVariablesForEventChain(constraint.getLeft());
            modifyConstraintVariablesForEventChain(constraint.getRight());
        }
    }

    private void modifyConstraintVariablesForEventChain(Expression expr) {
        if (expr.isVariable()) {
            String variable = expr.getVariable();

            if (variable.contains("<Input")) {
                String newVariable = variable.replaceFirst("<Input", "<ChainedInput");
                expr.set(newVariable, expr.getType());
            }

        } else if (expr.isExpression()) {
            modifyConstraintVariablesForEventChain(expr.getLeft());
            modifyConstraintVariablesForEventChain(expr.getRight());
        }
    }

    // ========================================================================

    private void processSharedPrefDependencies() {
        constraintContainsSharedPrefDependencies(_constraints);
    }

    private void constraintContainsSharedPrefDependencies(Predicate constraint) {
        if (constraint.isExpression()) {
            exprContainsSharedPrefDependencies(constraint.getExpression());

        } else if (constraint.isUnary()) {
            constraintContainsSharedPrefDependencies(constraint.getLeft());

        } else if (constraint.isBinary()) {
            constraintContainsSharedPrefDependencies(constraint.getLeft());
            constraintContainsSharedPrefDependencies(constraint.getRight());
        }
    }

    private String exprContainsSharedPrefDependencies(Expression expr) {
        if (expr == null) {
            return null;
        }

        if (expr.isVariable()) {
            List<String> result = new ArrayList<String>();
            String variable = expr.getVariable();

            if (variable.contains("SharedPreferences<")) {
                String key = variable.substring(variable.indexOf("<") + 1, variable.lastIndexOf(">"));
                return key;
            }

            return null;

        } else if (expr.isExpression()) {
            String leftResult = exprContainsSharedPrefDependencies(expr.getLeft());
            String rightResult = exprContainsSharedPrefDependencies(expr.getRight());

            if (leftResult != null) {
                _sharedPrefDependencies.put(leftResult, expr);
            } else if (rightResult != null) {
                _sharedPrefDependencies.put(rightResult, expr);
            }

            return null;
        }

        return null;
    }

    // ========================================================================

    private Map<Integer, ExpressionGroup> processPathNode(int pathIndex, Map<Integer, ExpressionGroup> parameterMap) {
        CallGraph cg = _callPath.getCallGraph();
        List<CGNode> path = _callPath.getPath();
        CGNode node = path.get(pathIndex);
        SSACFG cfg = node.getIR().getControlFlowGraph();
        Map<Integer, ExpressionGroup> nextParameterMap = null;

        if (parameterMap == null) {
            parameterMap = new HashMap<Integer, ExpressionGroup>();
        }

        if (parameterMap.isEmpty() && _storeDataMap.isEmpty() && pathIndex != 0) {
            Output.debug(DEBUG, "processPathNode: parameterMap is empty for " + node.toString());
            return null;
        }

        Output.debug(DEBUG, "processPathNode: " + node.toString());

        // Find the "target" instruction/basic block for this method
        SSAInstruction targetInstr = null;
        CGNode nextPathNode = null;

        if (pathIndex == path.size() - 1) {
            targetInstr = node.getIR().getInstructions()[_callPath.getTargetIndex()];
        } else {
            CallSiteReference targetCallSite =  cg.getPossibleSites(node, path.get(pathIndex + 1)).next();
            targetInstr = node.getIR().getPEI(new ProgramCounter(targetCallSite.getProgramCounter()));
            nextPathNode = path.get(pathIndex + 1);
        }

        ISSABasicBlock targetBlock = node.getIR().getBasicBlockForInstruction(targetInstr);

        // Store the propagated data values and constraints per basic block
        Map<ISSABasicBlock, Map<Integer, ExpressionGroup>> dataPropagationMap = new HashMap<ISSABasicBlock, Map<Integer, ExpressionGroup>>();
        Map<ISSABasicBlock, Predicate> constraintMap = new HashMap<ISSABasicBlock, Predicate>();

        dataPropagationMap.put(cfg.entry(), parameterMap);

        // Compute backedges to deap with loop dependencies
        IBinaryNaturalRelation backedges = Acyclic.computeBackEdges(cfg, cfg.entry());

        if (DEBUG) {
            Iterator<IntPair> backedgesIter = backedges.iterator();

            while(backedgesIter.hasNext()) {
                IntPair backedge = backedgesIter.next();
                final ISSABasicBlock sourceBB = cfg.getBasicBlock(backedge.getX());
                final ISSABasicBlock targetBB = cfg.getBasicBlock(backedge.getY());
                Output.debug(DEBUG, "backedge: " + backedge.getX() + " -> " + backedge.getY());

                DFSPathFinder<ISSABasicBlock> pathFinder = new DFSPathFinder<ISSABasicBlock>(cfg, targetBB, new Filter<ISSABasicBlock>() {
                    public boolean accepts(ISSABasicBlock block) {
                        if (block.equals(sourceBB)) {
                            return true;
                        }

                        return false;
                    }
                });

                List<ISSABasicBlock> loopPath = pathFinder.find();
                Collections.reverse(loopPath);

                for (ISSABasicBlock pathBlock : loopPath) {
                    Output.debug(DEBUG, "    path: " + pathBlock.getNumber());
                }
            }
        }

        // Iterative algorithm, to handle loops
        boolean changed = true;
        int iterationCount = 0;
        int iterationEnd = backedges.iterator().hasNext() ? 5 : 1;

        while (changed && iterationCount < iterationEnd) {
            Output.debug(DEBUG, "================ New Iteration =================");
            changed = false;
            iterationCount++;

            // Queue of blocks to be processed (in order)
            Queue<ISSABasicBlock> blockQueue = new LinkedList<ISSABasicBlock>();
            blockQueue.addAll(cfg.getNormalSuccessors(cfg.entry()));

            // For better performance, make queue unique
            Set<ISSABasicBlock> queuedBlocks = new HashSet<ISSABasicBlock>();
            queuedBlocks.addAll(cfg.getNormalSuccessors(cfg.entry()));

            // List of already processed blocks (to avoid infinite loops)
            Set<ISSABasicBlock> processedBlocks = new HashSet<ISSABasicBlock>();
            processedBlocks.add(cfg.entry());

            while (!blockQueue.isEmpty()) {
                ISSABasicBlock block = blockQueue.poll();
                queuedBlocks.remove(block);

                if (processedBlocks.contains(block)) {
                    continue;
                }

                //Output.debug(DEBUG, "Processing block: " + block.getNumber());

                Map<Integer, ExpressionGroup> dataPropagation = new HashMap<Integer, ExpressionGroup>();
                Predicate propagatedConstraints = null;
                Collection<ISSABasicBlock> predBlocks = cfg.getNormalPredecessors(block);

                boolean predBlocked = false;

                // Propagate constraints and data from predecessor blocks
                for (ISSABasicBlock predBlock : predBlocks) {
                    //Output.debug(DEBUG, "    pred: " + predBlock.getNumber());

                    if (!processedBlocks.contains(predBlock) && !predBlock.equals(block)) {
                        if (!backedges.contains(predBlock.getNumber(), block.getNumber())) {
                            if (!queuedBlocks.contains(predBlock)) {
                                blockQueue.offer(predBlock);
                                queuedBlocks.add(predBlock);
                            }

                            blockQueue.offer(block);
                            queuedBlocks.add(block);

                            predBlocked = true;
                            break;
                        }
                    }

                    if (!dataPropagationMap.containsKey(predBlock)) {
                        //Output.debug(DEBUG, "        pred block not in data propagation map");
                        continue;
                    }

                    dataPropagation.putAll(dataPropagationMap.get(predBlock));

                    Predicate predConstraint = null;
                    if (constraintMap.containsKey(predBlock)) {
                        predConstraint = constraintMap.get(predBlock);
                    }

                    if (predBlock.getFirstInstructionIndex() >= 0) {
                        if (Util.endsWithConditionalBranch(cfg, predBlock) && dataPropagationMap.containsKey(predBlock)) {
                            SSAConditionalBranchInstruction condInstr = (SSAConditionalBranchInstruction)predBlock.getLastInstruction();
                            //Predicate condConstraint = getConstraintFromCondInstr(node, condInstr, dataPropagationMap.get(predBlock));
                            ExpressionGroup condExpr = getExpressionFromCondInstr(node, condInstr, dataPropagationMap.get(predBlock));

                            if (condExpr != null) {
                                Predicate condConstraint = null;

                                if (block.equals(Util.getNotTakenSuccessor(cfg, predBlock))) {
                                    condConstraint = condExpr.toNotPredicate();
                                } else {
                                    condConstraint = condExpr.toPredicate();
                                }

                                if (!condConstraint.equals(predConstraint)) {
                                    predConstraint = Predicate.combine(Predicate.Operator.AND, predConstraint, condConstraint);
                                }
                            }
                        } else if (Util.endsWithSwitch(cfg, predBlock)) {
                            SSASwitchInstruction switchInstr = (SSASwitchInstruction)predBlock.getLastInstruction();
                            Map<Integer, ExpressionGroup> dataMap = dataPropagationMap.get(predBlock);

                            if (dataMap.containsKey(switchInstr.getUse(0))) {
                                if (Util.isSwitchDefault(cfg, predBlock, block)) {
                                    // TODO: Add "not" constraint for all other conditions
                                } else {
                                    int switchLabel = Util.getSwitchLabel(cfg, predBlock, block);
                                    //predConstraint = new Predicate(new Expression(Expression.Operator.EQ, dataMap.get(switchInstr.getUse(0)), new Predicate(Integer.toString(switchLabel), TypeReference.Int)));
                                    //Expression switchConstraint = null;
                                    //for (Expression switchExpr : dataMap.get(switchInstr.getUse(0))) {
                                    //    switchConstraint = Predicate.combine(Predicate.Operator.OR, switchConstraint, new Predicate(Integer.toString(switchLabel), TypeReference.Int));
                                    //}

                                    ExpressionGroup switchExprGrp = ExpressionGroup.combine(Expression.Operator.EQ, dataMap.get(switchInstr.getUse(0)), new Expression(Integer.toString(switchLabel), TypeReference.Int));
                                    predConstraint = switchExprGrp.toPredicate();
                                }
                            }
                        }
                    }

                    if (predConstraint != null) {
                        if (propagatedConstraints == null) {
                            propagatedConstraints = predConstraint;
                        } else if (propagatedConstraints.isOppositeOf(predConstraint)) {
                            propagatedConstraints = null;
                        } else if (propagatedConstraints.equals(predConstraint)) {

                        } else {
                            // TODO: Fix/resolve constraint explosion
                            // Problem: need to remove "opposite" constraints that are embedded into a longer 
                            // string of constraints (i.e. propagatedConstraints OR predConstraint results in
                            // certain expressions that are opposites).  The resulting constraint is techically
                            // correct, since heap variables are accessed and present in the constraints, but 
                            // a large number of conditional statements in a method could overwhelm the constraint
                            // processing.

                            propagatedConstraints = new Predicate(Predicate.Operator.OR, propagatedConstraints, predConstraint);
                        }
                    }
                }

                if (predBlocked) {
                    Output.debug(DEBUG, "    blocked by predecessor block");
                    continue;
                }

                Predicate newConstraints = null;

                // Propagate constraints through each instruction
                if (block.getFirstInstructionIndex() >= 0) {
                    for (SSAInstruction instr : ((SSACFG.BasicBlock)block).getAllInstructions()) {
                        Predicate instrConstraint = null;

                        try {
                            if (instr.equals(targetInstr) && nextPathNode != null) {
                                // Process next path node
                                nextParameterMap = generateDataMapForInvokedNode(nextPathNode, node, (SSAAbstractInvokeInstruction)instr, dataPropagation);
                            } else {
                                instrConstraint = propagateInstruction(node, instr, dataPropagation, true, targetInstr);
                            }
                        } catch (Exception e) {
                            Output.error("Exception: " + e.toString());
                            e.printStackTrace();
                        }

                        newConstraints = Predicate.combine(Predicate.Operator.AND, newConstraints, instrConstraint);
                    }
                }

                if (DEBUG) {
                    for (Integer dataVal : dataPropagation.keySet()) {
                        String dataName;

                        if (node.getIR().getLocalNames(0, dataVal) != null) {
                            dataName = node.getIR().getLocalNames(0, dataVal)[0];
                        } else {
                            dataName = node.getIR().getSymbolTable().getValueString(dataVal);
                        }

                        Output.debug(DEBUG, dataName + " maps to " + dataPropagation.get(dataVal).toString());
                    }
                }

                // Add block information to hash maps
                if (!dataPropagationMap.containsKey(block) || !dataPropagationMap.get(block).equals(dataPropagation)) {
                    changed = true;
                }

                dataPropagationMap.put(block, dataPropagation);

                Predicate blockConstraints = Predicate.combine(Predicate.Operator.AND, propagatedConstraints, newConstraints);

                if (blockConstraints != null) {
                    if (!constraintMap.containsKey(block) || !constraintMap.get(block).equals(blockConstraints)) {
                        changed = true;
                    }

                    constraintMap.put(block, blockConstraints);
                }

                if (block.equals(targetBlock)) {
                    break;
                }

                // Ignore any blocks that are not executed on the path to the required callsite
                if (block.getFirstInstructionIndex() >= 0 && Util.endsWithConditionalBranch(cfg, block)) {
                    SSAConditionalBranchInstruction condInstr = (SSAConditionalBranchInstruction)block.getLastInstruction();
                    int needTaken = findRequiredBranchDirection(node, condInstr, targetBlock);
                    ISSABasicBlock takenBlock = Util.getTakenSuccessor(cfg, block);
                    ISSABasicBlock notTakenBlock = Util.getNotTakenSuccessor(cfg, block);

                    if (needTaken > 0) {
                        if (!queuedBlocks.contains(takenBlock)) {
                            blockQueue.offer(takenBlock);
                            queuedBlocks.add(takenBlock);
                        }
                    } else if (needTaken < 0) {
                        if (!queuedBlocks.contains(notTakenBlock)) {
                            blockQueue.offer(notTakenBlock);
                            queuedBlocks.add(notTakenBlock);
                        }
                    } else {
                        //blockQueue.addAll(cfg.getNormalSuccessors(block));
                        for (ISSABasicBlock succBlock : cfg.getNormalSuccessors(block)) {
                            if (!queuedBlocks.contains(succBlock)) {
                                blockQueue.offer(succBlock);
                                queuedBlocks.add(succBlock);
                            }
                        }
                    }
                } else {
                    //blockQueue.addAll(cfg.getNormalSuccessors(block));
                    for (ISSABasicBlock succBlock : cfg.getNormalSuccessors(block)) {
                        if (!queuedBlocks.contains(succBlock)) {
                            blockQueue.offer(succBlock);
                            queuedBlocks.add(succBlock);
                        }
                    }
                }

                processedBlocks.add(block);

            } // while (!blockQueue.isEmpty())
        } // while (changed)

        if (DEBUG) {
            Output.debug(DEBUG, "-----------------------------------");
            Output.debug(DEBUG, "Propagation for: " + node.toString());
            Map<Integer, ExpressionGroup> dataMap = dataPropagationMap.get(targetBlock);

            //for (Integer dataVal : dataMap.keySet()) {
            //    String dataName;

            //    if (node.getIR().getLocalNames(0, dataVal) != null) {
            //        dataName = node.getIR().getLocalNames(0, dataVal)[0];
            //    } else {
            //        dataName = node.getIR().getSymbolTable().getValueString(dataVal);
            //    }

            //    Output.debug(DEBUG, dataName + " maps to " + dataMap.get(dataVal).toString());
            //}

            Output.debug(DEBUG, "-----------------------------------");

            //Output.debug(DEBUG, "Constraints for: " + node.toString());

            //for (ISSABasicBlock block : constraintMap.keySet()) {
            //    Output.debug(DEBUG, "Block: " + block.getNumber());
            //    Output.debug(DEBUG, "    " + constraintMap.get(block).toString());
            //}

            //Output.debug(DEBUG, "-----------------------------------");
        }

        Predicate nodeConstraints = constraintMap.get(targetBlock);
        if (nodeConstraints != null) {
            _pathNodeConstraints.add(nodeConstraints);
        }

        return nextParameterMap;
    }

    private Predicate processHelperNode(CGNode node, String returnString, Map<Integer, ExpressionGroup> parameterMap) {
        if (parameterMap == null) {
            parameterMap = new HashMap<Integer, ExpressionGroup>();
        }

        if (parameterMap.isEmpty() && _storeDataMap.isEmpty()) {
            Output.debug(DEBUG, "processHelperNode: parameterMap is empty for" + node.toString());
            return null;
        }

        if (node == null || node.getIR() == null) {
            return null;
        }

        Output.debug(DEBUG, "processHelperNode: " + node.toString());

        Predicate helperMethodConstraint = null;
        SSACFG cfg = node.getIR().getControlFlowGraph();
        SymbolTable symbolTable = node.getIR().getSymbolTable();

        // Propagate data through the instructions
        Map<ISSABasicBlock, Map<Integer, ExpressionGroup>> dataPropagationMap = new HashMap<ISSABasicBlock, Map<Integer, ExpressionGroup>>();

        dataPropagationMap.put(cfg.entry(), parameterMap);
        BFSIterator<ISSABasicBlock> bfsIter = new BFSIterator<ISSABasicBlock>(cfg, cfg.entry());

        // Skip the entry block
        bfsIter.next();

        while (bfsIter.hasNext()) {
            ISSABasicBlock block = bfsIter.next();

            Map<Integer, ExpressionGroup> dataPropagation = new HashMap<Integer, ExpressionGroup>();

            // Propagate data from predecessor blocks
            for (ISSABasicBlock predBlock : cfg.getNormalPredecessors(block)) {
                if (dataPropagationMap.containsKey(predBlock)) {
                    dataPropagation.putAll(dataPropagationMap.get(predBlock));
                }
            }

            // Propagate through each instruction
            if (block instanceof SSACFG.BasicBlock) {
                for (SSAInstruction instr : ((SSACFG.BasicBlock)block).getAllInstructions()) {
                    try {
                        propagateInstruction(node, instr, dataPropagation, false, null);
                    } catch (Exception e) {
                        Output.error("Exception: " + e.toString());
                        e.printStackTrace();
                    }
                }
            }

            // Add block information to hash maps
            dataPropagationMap.put(block, dataPropagation);
        }

        // Get all the return instructions
        List<SSAReturnInstruction> returnInstrs = new ArrayList<SSAReturnInstruction>();

        for (SSAInstruction instr : node.getIR().getInstructions()) {
            if (instr instanceof SSAReturnInstruction) {
                SSAReturnInstruction returnInstr = (SSAReturnInstruction)instr;

                if (returnInstr.getNumberOfUses() <= 0) {
                    continue;
                }

                returnInstrs.add(returnInstr);
            }
        }

        // For each return instruction, find all paths from entry 
        for (SSAReturnInstruction returnInstr : returnInstrs) {
            Predicate returnConstraint = null;
            final ISSABasicBlock returnBlock = node.getIR().getBasicBlockForInstruction(returnInstr);

            DFSPathFinder<ISSABasicBlock> pathFinder = new DFSPathFinder<ISSABasicBlock>(cfg, cfg.entry(), new Filter<ISSABasicBlock>() {
                public boolean accepts(ISSABasicBlock block) {
                    if (block.equals(returnBlock)) {
                        return true;
                    }

                    return false;
                }
            });

            do {
                Predicate pathConstraint = null;
                List<ISSABasicBlock> path = pathFinder.find();

                if (path == null) {
                    continue;
                }

                // Path blocks are in reverse order
                for (int pathIndex = path.size() - 1; pathIndex >= 0; pathIndex--) {
                    ISSABasicBlock pathBlock = path.get(pathIndex);

                    // Explore each path for conditional branches/constraints
                    if (pathBlock.getFirstInstructionIndex() >= 0 && Util.endsWithConditionalBranch(cfg, pathBlock)) {
                        SSAConditionalBranchInstruction condInstr = (SSAConditionalBranchInstruction)pathBlock.getLastInstruction();

                        // Get conditional branch requirement to go to next block
                        int needTaken = 0;
                        ISSABasicBlock takenBlock = Util.getTakenSuccessor(cfg, pathBlock);
                        ISSABasicBlock notTakenBlock = Util.getNotTakenSuccessor(cfg, pathBlock);
                        ISSABasicBlock nextPathBlock = path.get(pathIndex - 1);

                        if (nextPathBlock.equals(takenBlock)) {
                           needTaken = 1;
                        } else if (nextPathBlock.equals(notTakenBlock)) {
                           needTaken = -1;
                        }

                        if (needTaken == 0) {
                            Output.error("Error: cond. branch does not affect path direction");

                            if (DEBUG) {
                                Output.debug(DEBUG, "    " + condInstr);
                                Output.debug(DEBUG, "    next block: ");

                                for (SSAInstruction nextInstr : ((SSACFG.BasicBlock)path.get(pathIndex - 1)).getAllInstructions()) {
                                    Output.debug(DEBUG, "        " + nextInstr);
                                }

                                Output.debug(DEBUG, "");
                            }

                            continue;
                        }

                        //Predicate condConstraint = getConstraintFromCondInstr(node, condInstr, dataPropagationMap.get(node.getIR().getBasicBlockForInstruction(condInstr)));
                        ExpressionGroup condExpr = getExpressionFromCondInstr(node, condInstr, dataPropagationMap.get(node.getIR().getBasicBlockForInstruction(condInstr)));
                        if (condExpr == null) {
                            continue;
                        }

                        Predicate condConstraint = (needTaken < 0) ? (condExpr.toNotPredicate()) : (condExpr.toPredicate());
                        pathConstraint = Predicate.combine(Predicate.Operator.AND, pathConstraint, condConstraint);
                    }
                }

                if (pathConstraint != null && !pathConstraint.dependsOnInput()) {
                    pathConstraint = null;
                }

                if (pathConstraint != null && symbolTable.isIntegerConstant(returnInstr.getUse(0))) {
                    // Add constraint for return value to path constraints
                    ExpressionGroup returnValueExprGrp = getInstrOperandExpression(node, returnInstr.getUse(0), dataPropagationMap.get(returnBlock));
                    Predicate returnValueConstraint = ExpressionGroup.combine(Expression.Operator.EQ, new Expression(returnString, node.getMethod().getReturnType()), returnValueExprGrp).toPredicate();
                    pathConstraint = Predicate.combine(Predicate.Operator.AND, pathConstraint, returnValueConstraint);

                    // Add path constraint to set of return constraints for helper method
                    returnConstraint = Predicate.combine(Predicate.Operator.OR, returnConstraint, pathConstraint);
                }

                //if (pathConstraint != null && symbolTable.isIntegerConstant(returnInstr.getUse(0))) {
                //    Predicate returnValueConstraint = new Predicate(new Expression(Expression.Operator.EQ, new Expression(returnString, node.getMethod().getReturnType()), new Expression("" + symbolTable.getIntValue(returnInstr.getUse(0)), TypeReference.Int)));
                //    pathConstraint = Predicate.combine(Predicate.Operator.AND, pathConstraint, returnValueConstraint);

                //    if (returnConstraint == null) {
                //        returnConstraint = pathConstraint;
                //    } else {
                //        returnConstraint = new Predicate(Predicate.Operator.OR, returnConstraint, pathConstraint);
                //    }
                //} else if (dataPropagationMap.get(returnBlock).containsKey(returnInstr.getUse(0))){
                //    Predicate returnValueConstraint = new Predicate(new Expression(Expression.Operator.EQ, new Expression(returnString, node.getMethod().getReturnType()), dataPropagationMap.get(returnBlock).get(returnInstr.getUse(0))));
                //    if (returnConstraint == null) {
                //        returnConstraint = returnValueConstraint;
                //    } else {
                //        returnConstraint = new Predicate(Predicate.Operator.OR, returnConstraint, returnValueConstraint);
                //    }
                //}
            } while (pathFinder.hasNext());

            helperMethodConstraint = Predicate.combine(Predicate.Operator.OR, helperMethodConstraint, returnConstraint);

            if (returnConstraint != null) {
                Output.debug(DEBUG, "Return constraint for " + returnInstr);
                Output.debug(DEBUG, "    " + returnConstraint.toString());
                Output.debug(DEBUG, "");
            }
        }

        if (DEBUG) {
            Output.debug(DEBUG, "-----------------------------------");
            Output.debug(DEBUG, "Propagation for: " + node.toString());

            Map<Integer, ExpressionGroup> dataMap = new HashMap<Integer, ExpressionGroup>();
            for (ISSABasicBlock block : dataPropagationMap.keySet()) {
                dataMap.putAll(dataPropagationMap.get(block));
            }

            for (Integer dataVal : dataMap.keySet()) {
                String dataName;

                if (node.getIR().getLocalNames(0, dataVal) != null) {
                    dataName = node.getIR().getLocalNames(0, dataVal)[0];
                } else {
                    dataName = node.getIR().getSymbolTable().getValueString(dataVal);
                }

                Output.debug(DEBUG, dataName + " maps to " + dataMap.get(dataVal).toString());
            }

            Output.debug(DEBUG, "-----------------------------------");
        }

        return helperMethodConstraint;
    }

    private ExpressionGroup getExpressionFromCondInstr(CGNode node, SSAConditionalBranchInstruction condInstr, Map<Integer, ExpressionGroup> dataMap) {
        SymbolTable symbolTable = node.getIR().getSymbolTable();
        if (symbolTable == null) {
            return null;
        }

        if (!dataMap.containsKey(condInstr.getUse(0)) && !symbolTable.isConstant(condInstr.getUse(0))) {
            return null;
        }

        ExpressionGroup operand1 = getInstrOperandExpression(node, condInstr.getUse(0), dataMap);
        ExpressionGroup operand2 = getInstrOperandExpression(node, condInstr.getUse(1), dataMap);

        ExpressionGroup result = ExpressionGroup.combine(condInstr.getOperator(), operand1, operand2);
        Output.debug(DEBUG, "Cond instr: " + condInstr);
        Output.debug(DEBUG, "    expression: " + result);

        return result;
    }

    private Predicate propagateInstruction(CGNode node, SSAInstruction instr, Map<Integer, ExpressionGroup> dataMap, boolean propagateInvoke, SSAInstruction targetInstr) {
        SymbolTable symbolTable = node.getIR().getSymbolTable();

        if (instr instanceof SSAAbstractInvokeInstruction) {
            SSAAbstractInvokeInstruction invokeInstr = (SSAAbstractInvokeInstruction)instr;
            return propagateInvokeInstr(node, invokeInstr, dataMap, propagateInvoke, targetInstr);

        } else if (instr instanceof SSAGetInstruction) {
            SSAGetInstruction getInstr = (SSAGetInstruction)instr;
            return propagateGetInstr(node, getInstr, dataMap);

        } else if (instr instanceof SSAPutInstruction) {
            SSAPutInstruction putInstr = (SSAPutInstruction)instr;
            return propagatePutInstr(node, putInstr, dataMap);

        } else if (instr instanceof SSACheckCastInstruction) {
            SSACheckCastInstruction castInstr = (SSACheckCastInstruction)instr;

            if (dataMap.containsKey(castInstr.getUse(0))) {
                dataMap.put(castInstr.getDef(), dataMap.get(castInstr.getUse(0)));
            }
        } else if (instr instanceof SSAPhiInstruction) {
            SSAPhiInstruction phiInstr = (SSAPhiInstruction)instr;
            ExpressionGroup phiExprGrp = new ExpressionGroup();

            for (int i = 0; i < phiInstr.getNumberOfUses(); i++) {
                if (dataMap.containsKey(phiInstr.getUse(i))) {
                    //dataMap.put(phiInstr.getDef(), dataMap.get(phiInstr.getUse(i)));
                    phiExprGrp.addAll(dataMap.get(phiInstr.getUse(i)));
                }
            }

            if (!phiExprGrp.isEmpty()) {
                dataMap.put(phiInstr.getDef(), phiExprGrp);
            }
        } else if (instr instanceof SSAComparisonInstruction) {
            SSAComparisonInstruction compareInstr = (SSAComparisonInstruction)instr;

            if ((dataMap.containsKey(compareInstr.getUse(0)) || symbolTable.isConstant(compareInstr.getUse(0))) &&
                (dataMap.containsKey(compareInstr.getUse(1)) || symbolTable.isConstant(compareInstr.getUse(1)))) {

                ExpressionGroup left = getInstrOperandExpression(node, compareInstr.getUse(0), dataMap);
                ExpressionGroup right = getInstrOperandExpression(node, compareInstr.getUse(1), dataMap);
                ExpressionGroup cmpExprGrp = ExpressionGroup.combine(Expression.Operator.CMP, left, right);
                if (cmpExprGrp != null && !cmpExprGrp.isEmpty()) {
                    dataMap.put(compareInstr.getDef(), cmpExprGrp);
                }
            }
        } else if (instr instanceof SSABinaryOpInstruction) {
            SSABinaryOpInstruction binaryInstr = (SSABinaryOpInstruction)instr;

            // Heuristic to generate time constraint (in cases where the time is stored on the heap)
            if (binaryInstr.getOperator().equals(IBinaryOpInstruction.Operator.SUB) && dataMap.containsKey(binaryInstr.getUse(0))) {
                ExpressionGroup.Callable<Boolean> checkTimeFunc = new ExpressionGroup.Callable<Boolean>() {
                    @Override
                    public Boolean call(Expression expr) {
                        return expr.isVariable() && expr.getVariable().equals("System.currentTimeMillis()");
                    }
                };
                
                if (dataMap.get(binaryInstr.getUse(0)).evaluate(checkTimeFunc)) {
                    ExpressionGroup.Callable<Boolean> checkPointerFunc = new ExpressionGroup.Callable<Boolean>() {
                        @Override
                        public Boolean call(Expression expr) {
                            return expr.isVariable() && expr.getVariable().contains("Pointer<");
                        }
                    };

                    if (!dataMap.containsKey(binaryInstr.getUse(1)) || dataMap.get(binaryInstr.getUse(1)).evaluate(checkPointerFunc)) {
                        dataMap.put(binaryInstr.getUse(1), new ExpressionGroup(new Expression("<SystemPrevTime>", TypeReference.Long)));
                    }
                }
            }

            //if (dataMap.containsKey(binaryInstr.getUse(0)) && 
            //    dataMap.get(binaryInstr.getUse(0)).isVariable() &&
            //    dataMap.get(binaryInstr.getUse(0)).getVariable().equals("System.currentTimeMillis()") &&
            //    binaryInstr.getOperator().equals(IBinaryOpInstruction.Operator.SUB)) {

            //    if (!dataMap.containsKey(binaryInstr.getUse(1)) || (dataMap.get(binaryInstr.getUse(1)).get(0).isVariable() && dataMap.get(binaryInstr.getUse(1)).get(0).getVariable().contains("Pointer<"))) {
            //        dataMap.put(binaryInstr.getUse(1), new Predicate("<SystemPrevTime>", TypeReference.Long));
            //    }
            //}

            if ((dataMap.containsKey(binaryInstr.getUse(0)) || symbolTable.isConstant(binaryInstr.getUse(0))) &&
                (dataMap.containsKey(binaryInstr.getUse(1)) || symbolTable.isConstant(binaryInstr.getUse(1)))) {

                ExpressionGroup left = getInstrOperandExpression(node, binaryInstr.getUse(0), dataMap);
                ExpressionGroup right = getInstrOperandExpression(node, binaryInstr.getUse(1), dataMap);
                ExpressionGroup binaryExprGrp = ExpressionGroup.combine(binaryInstr.getOperator(), left, right);
                dataMap.put(binaryInstr.getDef(), binaryExprGrp);
            }
        } else if (instr instanceof SSAConversionInstruction) {
            if (dataMap.containsKey(instr.getUse(0))) {
                dataMap.put(instr.getDef(), dataMap.get(instr.getUse(0)));
            }
        } else if (instr instanceof SSAArrayLoadInstruction) {
            SSAArrayLoadInstruction arrayInstr = (SSAArrayLoadInstruction)instr;

            if (dataMap.containsKey(arrayInstr.getArrayRef())) {
                dataMap.put(instr.getDef(), dataMap.get(arrayInstr.getArrayRef()));
            }
        } else if (instr instanceof SSAArrayStoreInstruction) {
            SSAArrayStoreInstruction arrayInstr = (SSAArrayStoreInstruction)instr;
            int storeVal = arrayInstr.getUse(arrayInstr.getNumberOfUses() - 1);

            if (dataMap.containsKey(storeVal)) {
                dataMap.put(arrayInstr.getArrayRef(), dataMap.get(storeVal));
            }
        }

        return null;
    }

    private Predicate propagateInvokeInstr(final CGNode node, final SSAAbstractInvokeInstruction invokeInstr, Map<Integer, ExpressionGroup> dataMap, final boolean propagateInvoke, final SSAInstruction targetInstr) {
        final MethodReference target = invokeInstr.getDeclaredTarget();
        final String targetName = target.getSelector().getName().toString();
        final String targetSignature = target.getSignature();

        final ExpressionGroup recvExpr = invokeInstr.getNumberOfUses() > 0 ? getInstrOperandExpression(node, invokeInstr.getUse(0), dataMap) : null;
        final ExpressionGroup paramExpr = invokeInstr.getNumberOfUses() > 1 ? getInstrOperandExpression(node, invokeInstr.getUse(1), dataMap) : null;
        final ExpressionGroup staticParamExpr = recvExpr;

        //Output.debug(DEBUG, "invoke: " + target.getSignature());

        if (targetName.equals("set") && invokeInstr.getNumberOfUses() > 1) {
            int copiedVal = invokeInstr.getUse(1);

            if (dataMap.containsKey(copiedVal)) {
                dataMap.put(invokeInstr.getReceiver(), dataMap.get(copiedVal));
            }
        } else if (targetName.equals("equals") || targetName.equals("equalsIgnoreCase") || targetName.equals("matches") || targetName.equals("startsWith") || targetName.equals("endsWith") || targetName.equals("substring") || targetName.equals("contains")) {
            if (invokeInstr.getNumberOfUses() > 1) {
                if (recvExpr != null && paramExpr != null) {
                    String returnString = targetName + invokeInstr.getDef() + "<return>";
                    dataMap.put(invokeInstr.getDef(), new ExpressionGroup(new Expression(returnString, invokeInstr.getDeclaredTarget().getReturnType())));

                    // Represent string comparison methods as constraints
                    //Predicate eqConstraint = new Predicate(new Expression(Expression.Operator.EQ, recvExpr, paramExpr));
                    Predicate eqConstraint = ExpressionGroup.combine(Expression.Operator.EQ, recvExpr, paramExpr).toPredicate();
                    Expression eqRetExpression = new Expression(Expression.Operator.EQ, new Expression(returnString, TypeReference.Int), new Expression("1", TypeReference.Int));
                    eqConstraint = new Predicate(Predicate.Operator.AND, eqConstraint, new Predicate(eqRetExpression));

                    //Predicate neConstraint = new Predicate(new Expression(Expression.Operator.NE, recvExpr, paramExpr));
                    Predicate neConstraint = ExpressionGroup.combine(Expression.Operator.NE, recvExpr, paramExpr).toPredicate();
                    Expression neRetExpression = new Expression(Expression.Operator.EQ, new Expression(returnString, TypeReference.Int), new Expression("0", TypeReference.Int));
                    neConstraint = new Predicate(Predicate.Operator.AND, neConstraint, new Predicate(neRetExpression));

                    Predicate constraint = new Predicate(Predicate.Operator.OR, eqConstraint, neConstraint);
                    return constraint;
                }
            }
        } else if (targetSignature.contains("android.os.Message.obtain(")) {
            if (invokeInstr.getNumberOfParameters() > 2) {
                int objVal = invokeInstr.getUse(invokeInstr.getNumberOfParameters() - 1);

                if (dataMap.containsKey(objVal)) {
                    dataMap.put(-1, dataMap.get(objVal));
                }

                if (invokeInstr.getNumberOfParameters() > 3) {
                    int arg1Val = invokeInstr.getUse(2);
                    int arg2Val = invokeInstr.getUse(3);

                    if (dataMap.containsKey(arg1Val)) {
                        dataMap.put(-2, dataMap.get(arg1Val));
                    }

                    if (dataMap.containsKey(arg2Val)) {
                        dataMap.put(-3, dataMap.get(arg2Val));
                    }
                }
            }
        } else if (targetSignature.startsWith("android.content.SharedPreferences.get")) {
            if (paramExpr != null) {
                ExpressionGroup.Callable<Expression> sharedPrefsFunc = new ExpressionGroup.Callable<Expression>() {
                    @Override
                    public Expression call(Expression expr) {
                        if (expr.isVariable()) {
                            return new Expression("SharedPreferences<" + expr.getVariable() + ">", target.getReturnType());
                        }
                        return null;
                    }
                };

                ExpressionGroup sharedPrefExprGrp = ExpressionGroup.extract(sharedPrefsFunc, paramExpr);
                if (!sharedPrefExprGrp.isEmpty()) {
                    dataMap.put(invokeInstr.getDef(), sharedPrefExprGrp);
                }
            }

            //if (paramExpr != null && paramExpr.isVariable()) {
            //    Expression sharedPrefExpr = new Expression("SharedPreferences<" + paramExpr.getVariable() + ">", target.getReturnType());
            //    dataMap.put(invokeInstr.getDef(), new ExpressionGroup(sharedPrefExpr));
            //}
        } else if (targetSignature.startsWith("java.text.SimpleDateFormat.<init>(Ljava/lang/String;")) {
            ExpressionGroup.Callable<Expression> dateFormatFunc = new ExpressionGroup.Callable<Expression>() {
                @Override
                public Expression call(Expression expr) {
                    if (expr.isVariable()) {
                        return new Expression("DateFormat(" + expr.getVariable() + ")", TypeReference.JavaLangString);
                    }
                    return null;
                }
            };

            ExpressionGroup dateExprGrp = ExpressionGroup.extract(dateFormatFunc, paramExpr);
            if (!dateExprGrp.isEmpty()) {
                dataMap.put(invokeInstr.getDef(), dateExprGrp);
            }

            //if (paramExpr != null && paramExpr.isVariable()) {
            //    String dateString = "DateFormat(" + paramExpr.getVariable() + ")";
            //    Expression dateExpr = new Expression(dateString, TypeReference.JavaLangString);
            //    dataMap.put(invokeInstr.getReceiver(), Arrays.asList(dateExpr));
            //}
        } else if (targetSignature.contains("DateFormat.format(Ljava/util/Date;")) {
            if (recvExpr != null && paramExpr != null) {
                ExpressionGroup dateExprGrp = new ExpressionGroup();
                for (Expression rExpr : recvExpr.toList()) {
                    for (Expression pExpr : paramExpr.toList()) {
                        if (pExpr.isVariable() && rExpr.isVariable()) {
                            dateExprGrp.add(new Expression(rExpr.getVariable() + "(" + pExpr.getVariable() + ")", TypeReference.JavaLangString));
                        }
                    }
                }

                if (!dateExprGrp.isEmpty()) {
                    dataMap.put(invokeInstr.getDef(), dateExprGrp);
                }
            }

            //if (recvExpr != null && recvExpr.get(0).isVariable() && paramExpr != null && paramExpr.get(0).isVariable()) {

            //    String dateString = recvExpr.getVariable() + "(" + paramExpr.getVariable() + ")";
            //    Expression dateExpr = new Expression(dateString, TypeReference.JavaLangString);
            //    dataMap.put(invokeInstr.getDef(), Arrays.asList(dateExpr));
            //}
        } else if (targetName.equals("findViewById")) {
            Expression uiExpr = new Expression("<UI>", TypeReference.JavaLangString);
            dataMap.put(invokeInstr.getDef(), new ExpressionGroup(uiExpr));
        } else if (targetName.equals("toString") || targetName.equals("getString") || targetName.equals("trim") || targetName.equals("toLowerCase")) {
            //if (recvExpr != null && recvPred.get(0).isVariable()) {
            if (recvExpr != null) {
                dataMap.put(invokeInstr.getDef(), recvExpr);
            }
        } else if (targetSignature.startsWith("java.lang.String.valueOf(") || 
                   targetSignature.startsWith("java.lang.Integer.valueOf(")) {
            //if (staticParamExpr != null && staticParamExpr.isVariable()) {
            if (staticParamExpr != null) {
                dataMap.put(invokeInstr.getDef(), staticParamExpr);
            }
        } else if (targetSignature.contains("java.lang.StringBuilder.append(")) {
            ExpressionGroup.Callable<Boolean> checkPropagateFunc = new ExpressionGroup.Callable<Boolean>() {
                @Override
                public Boolean call(Expression expr) {
                    return (expr != null) && (expr.isVariable()) && (expr.getVariable().startsWith("Pointer<") || expr.getVariable().startsWith("<Input"));
                }
            };

            if (paramExpr != null && paramExpr.evaluate(checkPropagateFunc)) {
                dataMap.put(invokeInstr.getDef(), paramExpr);
            } else if (recvExpr != null && recvExpr.evaluate(checkPropagateFunc)) {
                dataMap.put(invokeInstr.getDef(), recvExpr);
            } else if (paramExpr != null && recvExpr != null) {
                ExpressionGroup appendExprGrp = new ExpressionGroup();

                for (Expression rExpr : recvExpr.toList()) {
                    for (Expression pExpr : paramExpr.toList()) {
                        if (rExpr.isVariable() && pExpr.isVariable()) {
                            String appendString = rExpr.getVariable() + pExpr.getVariable();
                            appendExprGrp.add(new Expression(appendString, TypeReference.JavaLangString));
                        }
                    }
                }

                if (!appendExprGrp.isEmpty()) {
                    dataMap.put(invokeInstr.getDef(), appendExprGrp);
                }
            }

            //if (paramExpr != null && paramExpr.isVariable() && (paramExpr.get(0).getVariable().startsWith("Pointer<") || paramExpr.get(0).getVariable().startsWith("<Input"))) {
            //    dataMap.put(invokeInstr.getDef(), paramExpr);
            //} else if (recvPred != null && recvPred.isVariable() && (recvPred.getVariable().startsWith("Pointer<") || recvPred.getVariable().startsWith("<Input"))) {
            //    dataMap.put(invokeInstr.getDef(), recvPred);
            //} else if (recvPred != null && recvPred.isVariable() && 
            //           paramPred != null && paramPred.isVariable()) {

            //    String appendString = recvPred.getVariable() + paramPred.getVariable();
            //    Predicate appendStringPred = new Predicate(appendString, TypeReference.JavaLangString);
            //    dataMap.put(invokeInstr.getDef(), appendStringPred);
            //}

        } else if (_essentialMethods.containsKey(targetSignature)) {
            if (invokeInstr.getNumberOfUses() > 0 && dataMap.containsKey(invokeInstr.getUse(0))) {
                dataMap.put(invokeInstr.getDef(), new ExpressionGroup(new Expression(dataMap.get(invokeInstr.getUse(0)) + "." + _essentialMethods.get(targetSignature), target.getReturnType())));
            } else {
                if (targetName.contains("<init>")) {
                    dataMap.put(invokeInstr.getReceiver(), new ExpressionGroup(new Expression(_essentialMethods.get(targetSignature), target.getReturnType())));
                } else {
                    dataMap.put(invokeInstr.getDef(), new ExpressionGroup(new Expression(_essentialMethods.get(targetSignature), target.getReturnType())));
                }
            }
        } else if (targetName.equals("<init>") && target.getNumberOfParameters() == 0) {
            // Ignore constructors without parameters

        } else if (targetName.equals("<init>") && target.getNumberOfParameters() > 0 && !target.getDeclaringClass().isPrimitiveType() && !target.getParameterType(0).isPrimitiveType()) {
            IClass targetClass = _cha.lookupClass(target.getDeclaringClass());
            IClass receiverClass = _cha.lookupClass(target.getParameterType(0));

            if (targetClass != null && receiverClass != null && _cha.isAssignableFrom(targetClass, receiverClass)) {
                int copiedVal = invokeInstr.getUse(1);
                ExpressionGroup copiedExprGrp = getInstrOperandExpression(node, copiedVal, dataMap);

                if (copiedExprGrp != null) {
                    dataMap.put(invokeInstr.getReceiver(), copiedExprGrp);
                }
            }
        //} else if (invokeInstr.getNumberOfUses() > 0 && dataMap.containsKey(invokeInstr.getReceiver()) && dataMap.get(invokeInstr.getReceiver()).dependsOnInput()) {
        //    String dataString = dataMap.get(invokeInstr.getReceiver()) + "." + target.getName() + "(";

        //    if (!invokeInstr.isStatic() && invokeInstr.getNumberOfUses() == 2) {
        //        if (symbolTable.isStringConstant(invokeInstr.getUse(1))) {
        //            dataString += symbolTable.getStringValue(invokeInstr.getUse(1));
        //        }
        //    }

        //    dataString += ")";
        //    dataMap.put(invokeInstr.getDef(), new Expression(dataString, invokeInstr.getDeclaredTarget().getReturnType()));

        } else if (invokeInstr.getCallSite().equals(_callPath.getTarget())) {
            for (int i = invokeInstr.isStatic() ? 0 : 1; i < invokeInstr.getNumberOfUses(); i++) {
                ExpressionGroup targetParamExprGrp = getInstrOperandExpression(node, invokeInstr.getUse(i), dataMap);

                if (targetParamExprGrp != null) {
                    _targetParameters.addAll(targetParamExprGrp.toList());
                }
            }

        } else {
            if (invokeInstr.getNumberOfUses() > 0 && !invokeInstr.isStatic()) {
                if (recvExpr != null) {
                    ExpressionGroup.Callable<Expression> propInputFunc = new ExpressionGroup.Callable<Expression>() {
                        @Override
                        public Expression call(Expression expr) {
                            if (expr.isVariable() && expr.getVariable().startsWith("<Input")) {
                                return new Expression(expr.getVariable() + "." + targetName + "()", invokeInstr.getDeclaredResultType());
                            }
                            return null;
                        }
                    };

                    ExpressionGroup invokeExprGrp = ExpressionGroup.extract(propInputFunc, recvExpr);
                    if (!invokeExprGrp.isEmpty()) {
                        dataMap.put(invokeInstr.getDef(), invokeExprGrp);
                        return null;
                    }
                }

                //if (recvPred != null && recvPred.isVariable() && recvPred.getVariable().startsWith("<Input")) {
                //    String dataString = recvPred.getVariable() + "." + targetName + "()";
                //    Predicate newPred = new Predicate(dataString, invokeInstr.getDeclaredResultType());
                //    dataMap.put(invokeInstr.getDef(), newPred);
                //    return null;
                //}
            }

            Set<CGNode> possibleNodes = _callPath.getCallGraph().getPossibleTargets(node, invokeInstr.getCallSite());

            if (!possibleNodes.isEmpty()) {
                CGNode invokedNode = possibleNodes.iterator().next();

                if (propagateInvoke) {
                    if (invokeInstr.equals(targetInstr) && _callPath.containsNode(invokedNode)) {
                        Output.error("Error: propagateInvokeInstr: Processing next path node should not be done here");

                    } else if (invokedNode.getMethod().getDeclaringClass().getClassLoader().equals(ClassLoaderReference.Application)) {
                        // Process auxilliary methods in the application
                        String returnString = invokedNode.getMethod().getName().toString() + invokeInstr.getDef() + "<return>";
                        Predicate constraints = processHelperNode(invokedNode, returnString, generateDataMapForInvokedNode(invokedNode, node, invokeInstr, dataMap));

                        if (constraints != null) {
                            dataMap.put(invokeInstr.getDef(), new ExpressionGroup(new Expression(returnString, invokeInstr.getDeclaredTarget().getReturnType())));
                        }

                        return constraints;
                    }
                }
            }

            if (invokeInstr.getNumberOfUses() > 0 ) {
                String dataString = dataMap.get(invokeInstr.getReceiver()) + "." + targetName;

                if (dataMap.containsKey(invokeInstr.getReceiver()) && invokeInstr.getNumberOfUses() <= 2) {
                    if (invokeInstr.getNumberOfUses() > 1 && dataMap.containsKey(invokeInstr.getUse(1))) {
                        dataString += "(" + dataMap.get(invokeInstr.getUse(1)) + ")";
                    } else {
                        dataString += "()";
                    }

                    dataMap.put(invokeInstr.getDef(), new ExpressionGroup(new Expression(dataString, invokeInstr.getDeclaredTarget().getReturnType())));
                }
            }
        }

        return null;
    }

    private Predicate propagateGetInstr(CGNode node, SSAGetInstruction getInstr, Map<Integer, ExpressionGroup> dataMap) {
        if (getInstr.getDeclaredField().getSignature().equals("Landroid/os/Message.obj Ljava/lang/Object")) {
            if (dataMap.containsKey(-1)) {
                dataMap.put(getInstr.getDef(), dataMap.get(-1));
                dataMap.remove(-1);
            }
        } else if (getInstr.getDeclaredField().getSignature().equals("Landroid/os/Message.arg1 I")) {
            if (dataMap.containsKey(-2)) {
                dataMap.put(getInstr.getDef(), dataMap.get(-2));
                dataMap.remove(-2);
            }
        } else if (getInstr.getDeclaredField().getSignature().equals("Landroid/os/Message.arg2 I")) {
            if (dataMap.containsKey(-3)) {
                dataMap.put(getInstr.getDef(), dataMap.get(-3));
                dataMap.remove(-3);
            }
        } else {
            if (dataMap.containsKey(getInstr.getRef())) {
                dataMap.put(getInstr.getDef(), new ExpressionGroup(new Expression(dataMap.get(getInstr.getRef()) + "." + getInstr.getDeclaredField().getName().toString(), getInstr.getDeclaredFieldType())));
            } else {
                PointerAnalysis pa = _callPath.getPointerAnalysis();
                HeapModel heapModel = pa.getHeapModel();
                IClass declaringClass = _cha.lookupClass(getInstr.getDeclaredField().getDeclaringClass());

                if (declaringClass != null) {
                    IField field = null;

                    try {
                        field = declaringClass.getField(Atom.findOrCreate(getInstr.getDeclaredField().getName().toString().getBytes()));
                    } catch (IllegalStateException e) {
                        for (IField classField : declaringClass.getAllFields()) {
                            if (classField.getDeclaringClass().equals(declaringClass) && classField.getName().equals(getInstr.getDeclaredField().getName())) {
                                field = classField;
                                break;
                            }
                        }
                    }

                    if (field != null) {
                        PointerKey fieldPKey = null;

                        if (getInstr.isStatic()) {
                            if (field != null) {
                                fieldPKey = heapModel.getPointerKeyForStaticField(field);
                            }
                        } else {
                            PointerKey classPKey = heapModel.getPointerKeyForLocal(node, getInstr.getRef());
                            if (classPKey != null) {
                                Iterator<InstanceKey> classIKeyIter =  pa.getPointsToSet(classPKey).iterator();

                                if (classIKeyIter.hasNext()) {
                                    InstanceKey classIKey = classIKeyIter.next();

                                    if (classIKey != null) {
                                        fieldPKey = heapModel.getPointerKeyForInstanceField(classIKey, field);
                                    }
                                }
                            }
                        }

                        if (fieldPKey != null) {
                            String pKeyName = "Pointer<" + fieldPKey.hashCode() + ">";
                            ExpressionGroup pExprGrp = new ExpressionGroup(new Expression(pKeyName, TypeReference.Int));

                            if (_storeDataMap.containsKey(fieldPKey)) {
                                pExprGrp.addAll(_storeDataMap.get(fieldPKey));
                            } else {
                                _nameToPointerMap.put(pKeyName, fieldPKey);
                                _pointerToNameMap.put(fieldPKey, pKeyName);
                            }

                            dataMap.put(getInstr.getDef(), pExprGrp);

                            //if (_storeDataMap.containsKey(fieldPKey)) {
                            //    dataMap.put(getInstr.getDef(), _storeDataMap.get(fieldPKey));
                            //} else {
                            //    String pKeyName = "Pointer<" + fieldPKey.hashCode() + ">";

                            //    _nameToPointerMap.put(pKeyName, fieldPKey);
                            //    _pointerToNameMap.put(fieldPKey, pKeyName);

                            //    //dataMap.put(getInstr.getDef(), new Expression(pKeyName, getInstr.getDeclaredFieldType()));
                            //    dataMap.put(getInstr.getDef(), new ExpressionGroup(new Expression(pKeyName, TypeReference.Int)));
                            //}
                        }
                    }
                }
            }
        }

        return null;
    }

    private Predicate propagatePutInstr(CGNode node, SSAPutInstruction putInstr, Map<Integer, ExpressionGroup> dataMap) {
        int putVal = putInstr.getUse(putInstr.getNumberOfUses() - 1);

        if (putInstr.getDeclaredField().getSignature().equals("Landroid/os/Message.obj Ljava/lang/Object")) {
            if (dataMap.containsKey(putVal)) {
                dataMap.put(-1, dataMap.get(putVal));
            }
        } else if (putInstr.getDeclaredField().getSignature().equals("Landroid/os/Message.arg1 I")) {
            if (dataMap.containsKey(putVal)) {
                dataMap.put(-2, dataMap.get(putVal));
            }
        } else if (putInstr.getDeclaredField().getSignature().equals("Landroid/os/Message.arg2 I")) {
            if (dataMap.containsKey(putVal)) {
                dataMap.put(-3, dataMap.get(putVal));
            }
        } else {
            PointerAnalysis pa = _callPath.getPointerAnalysis();
            HeapModel heapModel = pa.getHeapModel();

            PointerKey fieldPKey = null;
            IClass declaringClass = _cha.lookupClass(putInstr.getDeclaredField().getDeclaringClass());

            if (declaringClass != null) {
                IField field = null;

                try {
                    field = declaringClass.getField(Atom.findOrCreate(putInstr.getDeclaredField().getName().toString().getBytes()));
                } catch (IllegalStateException e) {
                    for (IField classField : declaringClass.getAllFields()) {
                        if (classField.getDeclaringClass().equals(declaringClass) && classField.getName().equals(putInstr.getDeclaredField().getName())) {
                            field = classField;
                            break;
                        }
                    }
                }

                if (field != null) {
                    if (putInstr.isStatic()) {
                        if (field != null) {
                            fieldPKey = heapModel.getPointerKeyForStaticField(field);
                        }
                    } else {
                        PointerKey classPKey = heapModel.getPointerKeyForLocal(node, putInstr.getRef());
                        Iterator<InstanceKey> classIKeyIter = pa.getPointsToSet(classPKey).iterator();

                        while (classIKeyIter.hasNext()) {
                            InstanceKey classIKey = classIKeyIter.next();

                            if (classIKey != null) {
                                fieldPKey = heapModel.getPointerKeyForInstanceField(classIKey, field);

                                if (fieldPKey != null) {
                                    break;
                                }
                            }
                        }
                    }
                }
            }

            if (fieldPKey != null) {
                //_storedHeapLocations.add(fieldPKey);
                ExpressionGroup putExprGrp = getInstrOperandExpression(node, putVal, dataMap);

                if (putExprGrp != null) {
                    _storeDataMap.put(fieldPKey, putExprGrp);
                }
            }
        }

        return null;
    }

    private ExpressionGroup getInstrOperandExpression(CGNode node, int operandVal, Map<Integer, ExpressionGroup> dataMap) {
        if (dataMap.containsKey(operandVal)) {
            return dataMap.get(operandVal);
        }

        SymbolTable symbolTable = node.getIR().getSymbolTable();

        if (symbolTable.isConstant(operandVal)) {
            if (symbolTable.isIntegerConstant(operandVal)) {
                return new ExpressionGroup(new Expression(String.valueOf(symbolTable.getIntValue(operandVal)), TypeReference.Int));
            } else if (symbolTable.isFloatConstant(operandVal)) {
                return new ExpressionGroup(new Expression(String.valueOf(symbolTable.getFloatValue(operandVal)), TypeReference.Float));
            } else if (symbolTable.isDoubleConstant(operandVal)) {
                return new ExpressionGroup(new Expression(String.valueOf(symbolTable.getDoubleValue(operandVal)), TypeReference.Double));
            } else if (symbolTable.isLongConstant(operandVal)) {
                return new ExpressionGroup(new Expression(String.valueOf(symbolTable.getLongValue(operandVal)), TypeReference.Long));
            } else if (symbolTable.isStringConstant(operandVal)) {
                return new ExpressionGroup(new Expression(symbolTable.getStringValue(operandVal), TypeReference.JavaLangString));
            } else if (symbolTable.isNullConstant(operandVal)) {
                return new ExpressionGroup(new Expression("null", TypeReference.Void));
            } else {
                return new ExpressionGroup(new Expression(symbolTable.getConstantValue(operandVal).toString(), TypeReference.Void));
            }
        }

        return null;
    }

    private Map<Integer, ExpressionGroup> generateDataMapForInvokedNode(CGNode calleeNode, CGNode callerNode, SSAAbstractInvokeInstruction invokeInstr, Map<Integer, ExpressionGroup> dataMap) {
        HashMap<Integer, ExpressionGroup> nextDataMap = new HashMap<Integer, ExpressionGroup>();

        if (calleeNode != null && calleeNode.getIR() != null) {
            if (calleeNode.getIR().getNumberOfParameters() > 1 &&
                _cha.implementsInterface(calleeNode.getMethod().getDeclaringClass(), _runnableClass)) {

                DefUse defUse = callerNode.getDU();
                Iterator<SSAInstruction> runnableUseIter = defUse.getUses(invokeInstr.getUse(1));

                while (runnableUseIter.hasNext()) {
                    SSAInstruction runnableUseInstr = runnableUseIter.next();

                    if (runnableUseInstr instanceof SSAAbstractInvokeInstruction) {
                        SSAAbstractInvokeInstruction useInvokeInstr = (SSAAbstractInvokeInstruction)runnableUseInstr;
                        if (useInvokeInstr.getDeclaredTarget().getSelector().toString().startsWith("<init>")) {
                            for (int i = 0; i < useInvokeInstr.getNumberOfUses() && i < calleeNode.getIR().getNumberOfParameters(); i++) {
                                if (dataMap.containsKey(useInvokeInstr.getUse(i))) {
                                    nextDataMap.put(calleeNode.getIR().getParameter(i), dataMap.get(useInvokeInstr.getUse(i)));
                                }
                            }

                            return nextDataMap;
                        }
                    }
                }
            }

            for (int i = -3; i < 0; i++) {
                if (dataMap.containsKey(i)) {
                    nextDataMap.put(i, dataMap.get(i));
                }
            }

            for (int i = 0; i < invokeInstr.getNumberOfUses() && i < calleeNode.getIR().getNumberOfParameters(); i++) {
                if (dataMap.containsKey(invokeInstr.getUse(i))) {
                    nextDataMap.put(calleeNode.getIR().getParameter(i), dataMap.get(invokeInstr.getUse(i)));
                }
            }
        }

        return nextDataMap;
    }

    private Map<Integer, ExpressionGroup> generateCallbackDependencies(CGNode callerNode, SSAAbstractInvokeInstruction invokeInstr, Map<Integer, ExpressionGroup> dataMap) {
        HashMap<Integer, ExpressionGroup> nextDataMap = new HashMap<Integer, ExpressionGroup>();

        for (int i = -3; i < 0; i++) {
            if (dataMap.containsKey(i)) {
                nextDataMap.put(i, dataMap.get(i));
            }
        }

        for (int i = 0; i < invokeInstr.getNumberOfUses(); i++) {
            if (dataMap.containsKey(invokeInstr.getUse(i))) {
                ExpressionGroup.Callable<Expression> dependenciesFunc = new ExpressionGroup.Callable<Expression>() {
                    @Override
                    public Expression call(Expression expr) {
                        if (expr.dependsOnInput()) {
                            return expr;
                        }
                        return null;
                    }
                };

                ExpressionGroup dependency = ExpressionGroup.extract(dependenciesFunc, dataMap.get(invokeInstr.getUse(i)));
                if (dependency != null) {
                    nextDataMap.put(i, dependency);
                }

                //ExpressionGroup dependency = dataMap.get(invokeInstr.getUse(i));

                //if (dependency.dependsOnInput()) {
                //    nextDataMap.put(i, dependency);
                //}
            }
        }

        return nextDataMap;
    }

    private boolean isGetterMethod(CGNode node) {
        if (node == null) {
            return false;
        }

        if (node.getMethod().getReturnType().equals(TypeReference.Void)) {
            return false;
        }

        if (node.getIR() == null || node.getIR().getInstructions() == null) {
            return false;
        }

        //int numReturnInstr = 0;
        boolean foundReturnInstr = false;
        SSAReturnInstruction returnInstr = null;

        for (SSAInstruction instr : node.getIR().getInstructions()) {
            if (instr instanceof SSAReturnInstruction) {
                if (foundReturnInstr) {
                    return false;
                }

                foundReturnInstr = true;
                returnInstr = (SSAReturnInstruction)instr;

            } else if (instr instanceof SSAPutInstruction) {
                // Method has a side-effect
                return false;
            } else if (instr instanceof SSAAbstractInvokeInstruction) {
                // Method has a side-effect
                return false;
            }
        }

        SSAInstruction defInstr = node.getDU().getDef(returnInstr.getUse(0));

        if (defInstr instanceof SSAGetInstruction) {
            return true;
        }

        return false;
    }

    private String getFieldFromGetterMethod(CGNode node) {
        SSAReturnInstruction returnInstr = null;

        for (SSAInstruction instr : node.getIR().getInstructions()) {
            if (instr instanceof SSAReturnInstruction) {
                returnInstr = (SSAReturnInstruction)instr;
                break;
            }
        }

        SSAGetInstruction getInstr = (SSAGetInstruction)(node.getDU().getDef(returnInstr.getUse(0)));
        String fieldStr = getInstr.getDeclaredField().getName().toString();
        return fieldStr;
    }

    private int findRequiredBranchDirection(CGNode node, SSAConditionalBranchInstruction condInstr, SSAInstruction targetInstr) {
        ISSABasicBlock targetBlock = node.getIR().getBasicBlockForInstruction(targetInstr);
        return findRequiredBranchDirection(node, condInstr, targetBlock);
    }

    private int findRequiredBranchDirection(CGNode node, SSAConditionalBranchInstruction condInstr, final ISSABasicBlock targetBlock) {
        IR ir = node.getIR();
        SSACFG cfg = ir.getControlFlowGraph();

        ISSABasicBlock condBlock = ir.getBasicBlockForInstruction(condInstr);

        if (condBlock == null || targetBlock == null) {
            return 0;
        }

        ISSABasicBlock takenBlock = Util.getTakenSuccessor(cfg, condBlock);
        ISSABasicBlock notTakenBlock = Util.getNotTakenSuccessor(cfg, condBlock);

        Filter<ISSABasicBlock> targetFilter = new Filter<ISSABasicBlock>() {
            public boolean accepts(ISSABasicBlock o) {
                if (o.equals(targetBlock)) {
                    return true;
                }

                return false;
            }
        };

        int direction = 0;

        try {
            DFSPathFinder<ISSABasicBlock> takenPathFinder = new DFSPathFinder<ISSABasicBlock>(cfg, takenBlock, targetFilter);
            DFSPathFinder<ISSABasicBlock> notTakenPathFinder = new DFSPathFinder<ISSABasicBlock>(cfg, notTakenBlock, targetFilter);
            
            boolean takenPath = (takenPathFinder.find() != null);
            boolean notTakenPath = (notTakenPathFinder.find() != null);

            if (takenPath && !notTakenPath) {
                direction = 1;
            } else if (!takenPath && notTakenPath) {
                direction = -1;
            }
        } catch (OutOfMemoryError e) {
            Output.error("Out of Memory: " + e.toString());
            e.printStackTrace();
        } catch (Exception e) {
            Output.error("Exception: " + e.toString());
            e.printStackTrace();
        }

        return direction;
    }
}

