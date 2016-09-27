package intellidroid.appanalysis;

import com.ibm.wala.classLoader.*;
import com.ibm.wala.types.*;
import com.ibm.wala.ipa.cha.*;
import com.ibm.wala.ipa.callgraph.*;
import com.ibm.wala.ipa.callgraph.impl.*;
import com.ibm.wala.ipa.callgraph.propagation.*;
import com.ibm.wala.ipa.callgraph.propagation.cfa.*;
import com.ibm.wala.ssa.*;
import com.ibm.wala.util.config.AnalysisScopeReader;
import com.ibm.wala.util.strings.*;
import com.ibm.wala.util.graph.*;
import com.ibm.wala.util.graph.traverse.*;
import com.ibm.wala.util.collections.*;

import java.util.*;
import java.io.File;
import java.io.PrintWriter;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.BufferedReader;

import com.google.common.io.Files;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.google.gson.JsonPrimitive;

class TargetedPathsAnalysis {
    private final String _frameworkZ3Dir = "../FrameworkAnalysis/z3output/";

    private final EntrypointAnalysis _entrypointAnalysis;
    private final ManifestAnalysis _manifestAnalysis;
    private final UIActivityMapping _uiActivityAnalysis;
    private final CallGraphInfoListener _callGraphInfo;
    private final CallGraph _callGraph;
    private final PointerAnalysis _pointerAnalysis;

    int _callPathID = 0;

    //=========================================================================

    private class AndroidAppDFSPathFinder extends DFSPathFinder<CGNode> {
        public AndroidAppDFSPathFinder(Graph<CGNode> G, java.util.Iterator<CGNode> nodes, Filter<CGNode> f) {
            super (G, nodes, f);
        }

        public AndroidAppDFSPathFinder(Graph<CGNode> G, CGNode N, Filter<CGNode> f) {
            super (G, N, f);
        }

        @Override
        protected Iterator<CGNode> getConnected(CGNode node) {
            List<CGNode> appSuccNodes = new ArrayList<CGNode>();
            Iterator<CGNode> succNodesIter = G.getSuccNodes(node);

            while (succNodesIter.hasNext()) {
                CGNode succNode = succNodesIter.next();
                IClass succNodeClass = succNode.getMethod().getDeclaringClass();

                if (succNodeClass.getClassLoader().getReference().equals(ClassLoaderReference.Application) && 
                    !succNodeClass.getName().toString().startsWith("Landroid/support/v")) {
                    appSuccNodes.add(succNode);
                }
            }

            return appSuccNodes.iterator();
        }
    }

    //=========================================================================

    public TargetedPathsAnalysis(EntrypointAnalysis entrypointAnalysis, ManifestAnalysis manifestAnalysis, UIActivityMapping uiActivityAnalysis, CallGraphInfoListener callGraphInfo) {
        _entrypointAnalysis = entrypointAnalysis;
        _callGraph = entrypointAnalysis.getCallGraph();
        _pointerAnalysis = entrypointAnalysis.getPointerAnalysis();

        _manifestAnalysis = manifestAnalysis;
        _uiActivityAnalysis = uiActivityAnalysis;
        _callGraphInfo = callGraphInfo;
    }

    public void analyze() {
        JsonObject targetedPathsJson = new JsonObject();

        Collection<IMethod> entrypoints = _entrypointAnalysis.getEntrypoints();

        for (IMethod entrypoint : entrypoints) {
            LinkedHashMap<Integer, JsonObject> entrypointPathsJsonMap = analyzePathsFromEntrypoint(entrypoint);

            if (entrypointPathsJsonMap != null && !entrypointPathsJsonMap.isEmpty()) {
                for (Map.Entry<Integer, JsonObject> entrypointPathsEntry : entrypointPathsJsonMap.entrySet()) {
                    Integer callPathID = entrypointPathsEntry.getKey();
                    JsonObject entrypointPathJson = entrypointPathsEntry.getValue();
                    targetedPathsJson.add(callPathID.toString(), entrypointPathJson);
                }
            }
        }

        // Print call path and constraint information
        JsonObject appInfoJson = new JsonObject();
        appInfoJson.addProperty("packageName", _manifestAnalysis.getPackageName());
        appInfoJson.addProperty("mainActivity", _manifestAnalysis.getMainActivityName());
        appInfoJson.add("callPaths", targetedPathsJson);

        String appInfoFileName = "appInfo.json";

        try {
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            PrintWriter appInfoWriter = new PrintWriter(IntelliDroidAppAnalysis.Config.OutputDirectory + "/" + appInfoFileName, "UTF-8");
            appInfoWriter.print(gson.toJson(appInfoJson));
            appInfoWriter.close();
        } catch (Exception e) {
            System.err.println("Exception: " + e.toString());
            e.printStackTrace();
        }
    }

    private LinkedHashMap<Integer, JsonObject> analyzePathsFromEntrypoint(IMethod entrypoint) {
        Set<CGNode> entrypointNodes = _callGraph.getNodes(entrypoint.getReference());
        if (entrypointNodes.isEmpty()) {
            return null;
        }

        CGNode entrypointNode = entrypointNodes.iterator().next();
        Set<CallPath> callPaths = findCallPathsToMethods(entrypointNode);

        LinkedHashMap<Integer, JsonObject> entrypointPathsJsonMap = new LinkedHashMap<Integer, JsonObject>();

        for (CallPath callPath : callPaths) {
            try {
                JsonObject targetedPathJson = analyzeTargetedPath(callPath, _callPathID);

                if (targetedPathJson != null) {
                    entrypointPathsJsonMap.put(_callPathID, targetedPathJson);
                    _callPathID++;
                }

            } catch (Exception e) {
                System.err.println("Exception: " + e.toString());
                e.printStackTrace();
            }
        }

        return entrypointPathsJsonMap;
    }

    //=========================================================================

    private JsonObject analyzeTargetedPath(CallPath callPath, int callPathID) {
        // Extract constraints that govern path's execution
        ConstraintAnalysis constraintAnalyzer = new ConstraintAnalysis(callPath);
        Predicate constraints = constraintAnalyzer.getConstraints();
        ConstraintMinimization.minimize(constraints);

        if (IntelliDroidAppAnalysis.Config.PrintOutput) {
            printCallPath(callPathID, callPath, constraints);
        }

        if (constraints != null && constraints.isFalse()) {
            // If path not feasible, do not include it
            return null;
        }

        // Create event chain to handle dependent paths
        LinkedHashMap<CallPath, Predicate> eventChain = new LinkedHashMap<CallPath, Predicate>();
        eventChain.put(callPath, constraints);

        // Check for heap dependencies
        Map<PointerKey, Predicate> heapDependencies = constraintAnalyzer.getHeapDependencies();

        if (heapDependencies != null) {
            for (PointerKey pKey : heapDependencies.keySet()) {
                Predicate heapConstraint = Predicate.duplicate(heapDependencies.get(pKey));
                String pKeyName = constraintAnalyzer.getPointerKeyName(pKey);
                LinkedHashMap<CallPath, Predicate> heapConstraintChain = analyzeHeapConstraint(pKey, pKeyName, heapConstraint);

                if (heapConstraintChain != null && !heapConstraintChain.isEmpty()) {
                    eventChain.putAll(heapConstraintChain);
                }
            }
        } 

        // Check for dependencies on SharedPreferences (specific case of file dependencies)
        Map<String, Expression> sharedPrefDependencies = constraintAnalyzer.getSharedPrefDependencies();
        if (sharedPrefDependencies != null) {
            for (String key : sharedPrefDependencies.keySet()) {
                Expression sharedPrefExpression = sharedPrefDependencies.get(key);
                LinkedHashMap<CallPath, Predicate> sharedPrefConstraintChain = analyzeSharedPrefConstraint(key, sharedPrefExpression);

                if (sharedPrefConstraintChain != null && !sharedPrefConstraintChain.isEmpty()) {
                    eventChain.putAll(sharedPrefConstraintChain);
                }
            }
        }

        // Check for IPC dependencies
        MethodReference entrypointMethod = _entrypointAnalysis.getOverriddenFrameworkMethod(callPath.getPath().get(0).getMethod());
        String entrypointType = getCallbackType(entrypointMethod);

        if (!entrypointType.equals("activity") && !entrypointType.equals("service")) {
            LinkedHashMap<CallPath, Predicate> ipcConstraintChain = analyzeIPCConstraints(callPath.getPath().get(0));
            if (ipcConstraintChain != null && !ipcConstraintChain.isEmpty()) {
                eventChain.putAll(ipcConstraintChain);
            }
        }

        if (IntelliDroidAppAnalysis.Config.PrintOutput) {
            if (entrypointType.equals("ui")) {
                Set<TypeReference> uiActivities = _uiActivityAnalysis.getActivityForUIHandler(callPath.getPath().get(0).getMethod(), entrypointMethod);
                for (TypeReference uiActivity : uiActivities) {
                    Output.printPathInfo("    < UI activity >: " + uiActivity.getName());
                }
            }
        }

        // Create JSON object to store information about event chain
        JsonObject targetedPathJson = new JsonObject();
        targetedPathJson.addProperty("startMethod", callPath.getPath().get(0).getMethod().getSignature());
        targetedPathJson.addProperty("targetMethod", callPath.getTargetCallSite().getDeclaredTarget().getSignature());

        JsonArray eventChainJsonArray = new JsonArray();
        int z3FileNameID = 0;

        List<Map.Entry<CallPath, Predicate>> eventChainEntries = new ArrayList<Map.Entry<CallPath, Predicate>>(eventChain.entrySet());
        Collections.reverse(eventChainEntries);

        for (Map.Entry<CallPath, Predicate> eventChainEntry : eventChainEntries) {
            CallPath eventChainPath = eventChainEntry.getKey();
            Predicate eventChainPathConstraints = eventChainEntry.getValue();

            String z3ConstraintsFileName = null;

            if (eventChainPathConstraints != null) {
                z3ConstraintsFileName = "constraints" + callPathID + "_" + z3FileNameID + ".py";
                z3FileNameID++;
            }

            JsonObject eventChainPathJson = writePathAndZ3Constraints(eventChainPath, eventChainPathConstraints, z3ConstraintsFileName);
            eventChainJsonArray.add(eventChainPathJson);
        }

        targetedPathJson.add("eventChain", eventChainJsonArray);
        return targetedPathJson;
    }

    private LinkedHashMap<CallPath, Predicate> analyzeHeapConstraint(PointerKey pKey, String pKeyName, Predicate heapConstraint) {
        if (pKey == null) {
            return null;
        }

        LinkedHashMap<CallPath, Predicate> heapConstraintChain = new LinkedHashMap<CallPath, Predicate>();

        Set<CGNode> heapStoreNodes = _callGraphInfo.getHeapStoreNodes(pKey);
        if (heapStoreNodes == null) {
            return null;
        }

        CallPath storeCallPath = findCallPathToStore(heapStoreNodes, pKey, heapConstraint);
        if (storeCallPath == null) {
            return null;
        }

        ConstraintAnalysis storeConstraintAnalysis = new ConstraintAnalysis(storeCallPath);
        Predicate storeConstraints = storeConstraintAnalysis.getConstraints();

        if (storeConstraintAnalysis.getDataForPointerKey(pKey) != null) {
            if (storeConstraints != null) {
                storeConstraints = new Predicate(Predicate.Operator.AND, heapConstraint, storeConstraints);
            } else {
                storeConstraints = heapConstraint;
            }

            //Predicate pKeyNameConstraint = new Predicate(new Expression(Expression.Operator.EQ, new Expression(pKeyName, TypeReference.Int), storeConstraintAnalysis.getDataForPointerKey(pKey)));
            Predicate pKeyNameConstraint = ExpressionGroup.combine(Expression.Operator.EQ, new Expression(pKeyName, TypeReference.Int), storeConstraintAnalysis.getDataForPointerKey(pKey)).toPredicate();

            if (storeConstraints != null && pKeyNameConstraint != null) {
                storeConstraints = new Predicate(Predicate.Operator.AND, storeConstraints, pKeyNameConstraint);
            } else {
                storeConstraints = pKeyNameConstraint;
            }
        }

        ConstraintMinimization.minimize(storeConstraints);

        if (IntelliDroidAppAnalysis.Config.PrintOutput) {
            printHeapPath(storeCallPath, storeConstraints, pKey);
        }

        if (storeConstraints != null && storeConstraints.isFalse()) {
            // If path not feasible, do not include it
            return null;
        }

        heapConstraintChain.put(storeCallPath, storeConstraints);

        return heapConstraintChain;
    }

    private LinkedHashMap<CallPath, Predicate> analyzeSharedPrefConstraint(String key, Expression sharedPrefExpression) {
        //if (key == null) {
        //    return null;
        //}

        LinkedHashMap<CallPath, Predicate> sharedPrefConstraintChain = new LinkedHashMap<CallPath, Predicate>();

        if (_callGraphInfo.hasSharedPrefStore(key)) {
            CallPath storeCallPath = findCallPathToSharedPrefStore(_callGraphInfo.getSharedPrefStoreNodes(key), key, sharedPrefExpression);

            if (storeCallPath == null) {
                return null;
            }

            ConstraintAnalysis storeConstraintAnalysis = new ConstraintAnalysis(storeCallPath);
            Predicate storeConstraints = storeConstraintAnalysis.getConstraints();
            ConstraintMinimization.minimize(storeConstraints);

            //storeConstraints = Predicate.combine(Predicate.Operator.AND, sharedPrefConstraint, storeConstraints);

            if (IntelliDroidAppAnalysis.Config.PrintOutput) {
                printSharedPrefPath(storeCallPath, storeConstraints, key);
            }

            if (storeConstraints != null && storeConstraints.isFalse()) {
                // If path not feasible, do not include it
                return null;
            }

            sharedPrefConstraintChain.put(storeCallPath, storeConstraints);

        } else if (_callGraphInfo.hasSharedPrefUIStore(key)) {
            List<CGNode> uiNodePath = new ArrayList<CGNode>();
            uiNodePath.add(_callGraphInfo.getSharedPrefUIStore(key));

            CallPath uiCallPath = new CallPath(uiNodePath, -1, _callGraph, _pointerAnalysis);

            Predicate uiConstraint = new Predicate(new Expression(Expression.Operator.EQ, new Expression("SharedPreferences<" + key + ">", TypeReference.JavaLangString), new Expression("<UI>", TypeReference.JavaLangString)));
            sharedPrefConstraintChain.put(uiCallPath, uiConstraint);
            
            Output.printPathInfo("    " + _callGraphInfo.getSharedPrefUIStore(key));

            //if (_printConstraints) {
            //    uiConstraint.printExpression(1);
            //}
            Output.printConstraints(uiConstraint);
        }

        if (sharedPrefConstraintChain.isEmpty()) {
            Output.printPathInfo("    < no code path to SharedPreferences store >");
        }

        return sharedPrefConstraintChain;
    }

    private LinkedHashMap<CallPath, Predicate> analyzeIPCConstraints(CGNode entrypointNode) {
        IClassHierarchy cha = _callGraph.getClassHierarchy();

        LinkedHashMap<CallPath, Predicate> ipcEventChain = new LinkedHashMap<CallPath, Predicate>();

        IClass appCallbackClass = entrypointNode.getMethod().getDeclaringClass();
        MethodReference frameworkCallback = _entrypointAnalysis.getOverriddenFrameworkMethod(entrypointNode.getMethod());
        TypeReference frameworkCallbackClass = frameworkCallback.getDeclaringClass();

        Set<CGNode> regNodes = _callGraphInfo.getCallbackRegistrations(frameworkCallbackClass);

        if (regNodes == null) {
            if (frameworkCallbackClass.getName().toString().equals("Landroid/content/DialogInterface$OnDismissListener") ||
                frameworkCallbackClass.getName().toString().equals("Landroid/content/DialogInterface$OnCancelListener")) {

                frameworkCallbackClass = TypeReference.findOrCreate(ClassLoaderReference.Extension, "Landroid/app/DialogFragment");
                regNodes = _callGraphInfo.getCallbackRegistrations(frameworkCallbackClass);
            }
        }

        if (regNodes == null) {
            return null;
        }

        for (CGNode regNode : regNodes) {
            IR ir = regNode.getIR();
            DefUse defUse = regNode.getDU();
            SSAInstruction[] instructions = ir.getInstructions();

            for (int instrIndex = 0; instrIndex < instructions.length; instrIndex++) {
                SSAInstruction instr = instructions[instrIndex];

                if (!(instr instanceof SSAAbstractInvokeInstruction)) {
                    continue;
                }

                SSAAbstractInvokeInstruction invokeInstr = (SSAAbstractInvokeInstruction)instr;
                //MethodReference targetMethod = invokeInstr.getDeclaredTarget();
                IMethod targetMethod = cha.resolveMethod(invokeInstr.getDeclaredTarget());
                if (targetMethod == null) {
                    continue;
                }

                // TODO: distinguish between registration and de-registration methods
                String selectorString = targetMethod.getSelector().toString();
                if (selectorString.contains("remove") || selectorString.contains("unregister")) {
                    continue;
                }

                // Get handler type from registration
                boolean regMethodFound = false;
                TypeReference callbackType = null;

                for (int paramIndex = 0; paramIndex < targetMethod.getNumberOfParameters(); paramIndex++) {
                    if (targetMethod.getParameterType(paramIndex).getName().equals(frameworkCallbackClass.getName())) {
                        TypeReference registeredType = _uiActivityAnalysis.getHandlerTypeFromRegistration(regNode, invokeInstr, frameworkCallbackClass);
                        if (registeredType == null) {
                            break;
                        }

                        if (registeredType.equals(appCallbackClass.getReference())) {
                            regMethodFound = true;
                            break;
                        }
                    }
                }

                if (regMethodFound) {
                    // Find path/constraints for registration call path, add to event chain
                    CallPath ipcCallPath = findCallPathToCallbackRegistration(regNode, instrIndex);
                    if (ipcCallPath == null) {
                        continue;
                    }

                    ConstraintAnalysis ipcConstraintAnalysis = new ConstraintAnalysis(ipcCallPath);
                    Predicate ipcConstraints = ipcConstraintAnalysis.getConstraints();

                    if (IntelliDroidAppAnalysis.Config.PrintOutput) {
                        printCallbackRegistrationPath(ipcCallPath, ipcConstraints, appCallbackClass);
                    }

                    ipcEventChain.put(ipcCallPath, ipcConstraints);
                    return ipcEventChain;
                }
            }
        }

        return ipcEventChain;
    }

    //=========================================================================

    private Set<CallPath> findCallPathsToMethods(final CGNode rootNode) {
        Set<CallPath> callPaths = new HashSet<CallPath>();
        final IClassHierarchy cha = _callGraph.getClassHierarchy();
        final HeapModel heapModel = _pointerAnalysis.getHeapModel();

        for (String targetMethod : _callGraphInfo.getTargetMethods()) {
            final Set<CGNode> invokingNodes = _callGraphInfo.getTargetMethodInvokingNodes(targetMethod);

            Filter<CGNode> targetMethodFilter = new Filter<CGNode>() {
                @Override
                public boolean accepts(CGNode node) {
                    //System.out.println("node: " + node.getMethod().getSignature());
                    //if (node.getMethod().getSignature().contains("onCreateDialog")) {
                    //    printControlFlowGraph(node);
                    //}

                    if (invokingNodes.contains(node)) {
                        return true;
                    }

                    return false;
                }
            };

            DFSPathFinder<CGNode> pathFinder = new AndroidAppDFSPathFinder(_callGraph, rootNode, targetMethodFilter);
            //Set<CGNode> foundTargetMethods = new HashSet<CGNode>();

            do {
                List<CGNode> callPath = pathFinder.find();
                if (callPath == null) {
                    continue;
                }

                CGNode pathEndNode = callPath.get(0);
                //if (foundTargetMethods.contains(pathEndNode)) {
                //    continue;
                //}

                //foundTargetMethods.add(pathEndNode);

                Collections.reverse(callPath);

                SSAInstruction[] instructions = pathEndNode.getIR().getInstructions();

                for (int instrIndex = 0; instrIndex < instructions.length; instrIndex++) {
                    SSAInstruction instr = instructions[instrIndex];
                    if (instr == null) {
                        continue;
                    }

                    if (!(instr instanceof SSAAbstractInvokeInstruction)) {
                        continue;
                    }

                    SSAAbstractInvokeInstruction invokeInstr = (SSAAbstractInvokeInstruction)instr;
                    CallSiteReference callsite = invokeInstr.getCallSite();
                    IMethod invokedMethod = cha.resolveMethod(callsite.getDeclaredTarget());

                    if (invokedMethod != null && targetMethod.equals(invokedMethod.getSignature())) {
                        Statistics.trackPath(callPath, invokedMethod);

                        CallPath newPath = new CallPath(callPath, callsite, instrIndex, _callGraph, _pointerAnalysis);
                        callPaths.add(newPath);
                    }
                }
                
            } while (pathFinder.hasNext());
        }

        return callPaths;
    }

    private CallPath findCallPathToStore(final Set<CGNode> targetNodes, final PointerKey storePointer, final Predicate storeConstraint) {
        final IClassHierarchy cha = _callGraph.getClassHierarchy();
        final HeapModel heapModel = _pointerAnalysis.getHeapModel();

        Filter<CGNode> targetStoreFilter = new Filter<CGNode>() {
            @Override
            public boolean accepts(CGNode node) {
                if (targetNodes.contains(node)) {
                    return true;
                }

                return false;
            }
        };

        DFSPathFinder<CGNode> pathFinder = new AndroidAppDFSPathFinder(_callGraph, _callGraph.getFakeRootNode(), targetStoreFilter);

        do {
            List<CGNode> callPath = pathFinder.find();
            if (callPath == null) {
                return null;
            }

            CGNode pathEndNode = callPath.get(0);
            Collections.reverse(callPath);
            callPath.remove(0);

            SymbolTable symbolTable = pathEndNode.getIR().getSymbolTable();
            SSACFG cfg = pathEndNode.getIR().getControlFlowGraph();
            SSAInstruction[] instructions = cfg.getInstructions();

            for (int instrIndex = 0; instrIndex < instructions.length; instrIndex++) {
                SSAInstruction instr = instructions[instrIndex];
                if (instr == null) {
                    continue;
                }

                if (!(instr instanceof SSAPutInstruction)) {
                    continue;
                }

                SSAPutInstruction putInstr = (SSAPutInstruction)instr;
                IField field = cha.resolveField(putInstr.getDeclaredField());
                PointerKey fieldPKey = null;

                try {
                    if (putInstr.isStatic()) {
                        if (field != null) {
                            fieldPKey = heapModel.getPointerKeyForStaticField(field);
                        }
                    } else {
                        PointerKey classPKey = heapModel.getPointerKeyForLocal(pathEndNode, putInstr.getRef());
                        Iterator<InstanceKey> classIKeyIter = _pointerAnalysis.getPointsToSet(classPKey).iterator();

                        if (classIKeyIter.hasNext()) {
                            InstanceKey classIKey = classIKeyIter.next();

                            if (field != null) {
                                fieldPKey = heapModel.getPointerKeyForInstanceField(classIKey, field);
                            }
                        }
                    }

                    if (fieldPKey == null || !fieldPKey.equals(storePointer)) {
                        continue;
                    }

                    if (symbolTable.isConstant(putInstr.getVal())) {
                        String storeValue = getConstantValueString(symbolTable, putInstr.getVal());

                        Predicate storeValueConstraint = new Predicate(new Expression(Expression.Operator.EQ, new Expression("Pointer<" + storePointer.hashCode() + ">", field.getFieldTypeReference()), new Expression(storeValue, field.getFieldTypeReference())));
                        Predicate testConstraint = new Predicate(Predicate.Operator.AND, storeConstraint, storeValueConstraint);
                        ConstraintMinimization.minimize(testConstraint);

                        if (testConstraint == null || testConstraint.isFalse()) {
                            continue;
                        }
                    }

                    Statistics.trackPath(callPath, null);

                    CallPath newPath = null;
                    if (putInstr.isPEI()) {
                        newPath = new CallPath(callPath, new ProgramCounter(cfg.getProgramCounter(instrIndex)), instrIndex, _callGraph, _pointerAnalysis);
                    } else {
                        newPath = new CallPath(callPath, instrIndex, _callGraph, _pointerAnalysis);
                    }

                    return newPath;
                } catch (Exception e) {
                    System.err.println("Exception: " + e.toString());
                    e.printStackTrace();
                }
            }
        } while (pathFinder.hasNext());
            
        return null;
    }

    private CallPath findCallPathToSharedPrefStore(final Set<CGNode> targetNodes, final String key, final Expression sharedPrefExpression) {
        final IClassHierarchy cha = _callGraph.getClassHierarchy();
        final HeapModel heapModel = _pointerAnalysis.getHeapModel();

        Filter<CGNode> targetStoreFilter = new Filter<CGNode>() {
            @Override
            public boolean accepts(CGNode node) {
                if (targetNodes.contains(node)) {
                    return true;
                }

                return false;
            }
        };

        DFSPathFinder<CGNode> pathFinder = new AndroidAppDFSPathFinder(_callGraph, _callGraph.getFakeRootNode(), targetStoreFilter);

        do {
            List<CGNode> callPath = pathFinder.find();
            if (callPath == null) {
                continue;
            }

            CGNode pathEndNode = callPath.get(0);
            Collections.reverse(callPath);
            callPath.remove(0);

            SSAInstruction[] instructions = pathEndNode.getIR().getInstructions();

            for (int instrIndex = 0; instrIndex < instructions.length; instrIndex++) {
                SSAInstruction instr = instructions[instrIndex];

                if (instr == null) {
                    continue;
                }

                if (!(instr instanceof SSAAbstractInvokeInstruction)) {
                    continue;
                }

                SSAAbstractInvokeInstruction invokeInstr = (SSAAbstractInvokeInstruction)instr;
                IMethod targetMethod = cha.resolveMethod(invokeInstr.getDeclaredTarget());

                if (!AndroidMethods.isSharedPrefEditorMethod(targetMethod.getReference())) {
                    continue;
                }

                String sharedPrefKey = "";
                SymbolTable symbolTable = pathEndNode.getIR().getSymbolTable();

                if (symbolTable.isStringConstant(invokeInstr.getUse(1))) {
                    sharedPrefKey = symbolTable.getStringValue(invokeInstr.getUse(1));
                }

                if (!sharedPrefKey.equals(key)) {
                    continue;
                }

                Expression valueExpression = (sharedPrefExpression.getLeft().isVariable() && sharedPrefExpression.getLeft().getVariable().startsWith("SharedPreferences<")) ? sharedPrefExpression.getRight() : sharedPrefExpression.getLeft();

                if (valueExpression.isVariable() && symbolTable.isConstant(invokeInstr.getUse(2))) {
                    
                    Object sharedPrefObject = symbolTable.getConstantValue(invokeInstr.getUse(2));
                    String sharedPrefValue = "";
                    if (sharedPrefObject != null) {
                        sharedPrefValue = sharedPrefObject.toString();
                    } 
                    
                    // Ensure value being set matches the constraint
                    if (sharedPrefExpression.getOperator().equals(Expression.Operator.EQ) && !valueExpression.getVariable().equals(sharedPrefValue)) {
                        continue; 
                    }

                    if (Expression.isNotEqualOperator(sharedPrefExpression.getOperator()) && valueExpression.getVariable().equals(sharedPrefValue)) {
                        continue;
                    }
                }

                Statistics.trackPath(callPath, targetMethod);

                CallPath newPath = new CallPath(callPath, invokeInstr.getCallSite(), instrIndex, _callGraph, _pointerAnalysis);
                return newPath;
            }
        } while (pathFinder.hasNext());

        return null;
    }

    private CallPath findCallPathToUINotification(final CallPath callPath) {
        final IClassHierarchy cha = _callGraph.getClassHierarchy();
        Filter<CGNode> targetMethodFilter = new Filter<CGNode>() {
            @Override
            public boolean accepts(CGNode node) {
                IR ir = node.getIR();
                if (ir == null) {
                    return false;
                }

                Iterator<CallSiteReference> callSiteIter = ir.iterateCallSites();
                while (callSiteIter.hasNext()) {
                    CallSiteReference callsite = callSiteIter.next();

                    IMethod targetMethod = cha.resolveMethod(callsite.getDeclaredTarget());
                    if (targetMethod == null) {
                        continue;
                    }

                    if (AndroidMethods.isUINotificationMethod(targetMethod.getReference())) {
                        return true;
                    }
                }

                return false;
            }
        };

        for (int i = callPath.getPath().size() - 1; i >= 0; i--) {
            CGNode startNode = callPath.getPath().get(i);

            DFSPathFinder<CGNode> pathFinder = new AndroidAppDFSPathFinder(_callGraph, startNode, targetMethodFilter);

            List<CGNode> uiPath = pathFinder.find();
            if (uiPath != null) {
                CGNode pathEndNode = uiPath.get(0);
                Collections.reverse(uiPath);
                CallPath uiCallPath = null;
                //uiPath.remove(0);

                Iterator<CallSiteReference> callSiteIter = pathEndNode.getIR().iterateCallSites();
                while (callSiteIter.hasNext()) {
                    CallSiteReference callsite = callSiteIter.next();
                    IMethod targetMethod = cha.resolveMethod(callsite.getDeclaredTarget());
                    if (targetMethod == null) {
                        continue;
                    }

                    if (AndroidMethods.isUINotificationMethod(targetMethod.getReference())) {
                        uiCallPath = new CallPath(uiPath, callsite, -1, _callGraph, null);

                        Statistics.trackPath(uiPath, targetMethod);

                        if (IntelliDroidAppAnalysis.Config.PrintOutput) {
                            printUIPath(uiCallPath, null);
                        }

                        return uiCallPath;
                    }
                }
            }
        }

        return null;
    }

    private CallPath findCallPathToCallbackRegistration(final CGNode targetNode, final int targetInstrIndex) {
        Filter<CGNode> targetMethodFilter = new Filter<CGNode>() {
            @Override
            public boolean accepts(CGNode node) {
                if (node.equals(targetNode)) {
                    return true;
                }

                return false;
            }
        };

        for (IMethod entrypoint : _entrypointAnalysis.getEntrypoints()) {
            Set<CGNode> entrypointNodes= _callGraph.getNodes(entrypoint.getReference());
            if (entrypointNodes.isEmpty()) {
                continue;
            }

            CGNode entrypointNode = entrypointNodes.iterator().next();

            DFSPathFinder<CGNode> pathFinder = new AndroidAppDFSPathFinder(_callGraph, entrypointNode, targetMethodFilter);
            List<CGNode> callPath = pathFinder.find();
            if (callPath == null) {
                continue;
            }

            CGNode pathEndNode = callPath.get(0);
            Collections.reverse(callPath);

            SSAInstruction[] instructions = pathEndNode.getIR().getInstructions();
            SSAAbstractInvokeInstruction targetInstr = (SSAAbstractInvokeInstruction)instructions[targetInstrIndex];
            IMethod targetMethod = _callGraph.getClassHierarchy().resolveMethod(targetInstr.getDeclaredTarget());

            Statistics.trackPath(callPath, targetMethod);

            CallPath newPath = new CallPath(callPath, targetInstr.getCallSite(), targetInstrIndex, _callGraph, _pointerAnalysis);
            return newPath;
        }

        return null;
    }

    //=========================================================================

    private JsonObject writePathAndZ3Constraints(CallPath callPath, Predicate constraints, String z3FileName) {
        IMethod entryMethod = callPath.getPath().get(0).getMethod();
        ProgramCounter target = callPath.getTarget();
        MethodReference callbackOverrideMethod = _entrypointAnalysis.getOverriddenFrameworkMethod(entryMethod);
        String frameworkZ3FileName = callbackOverrideMethod == null ? "unknown" : getFileFriendlyName(callbackOverrideMethod);

        JsonObject constraintJson = new JsonObject();
        constraintJson.addProperty("start", callPath.getPath().get(0).getMethod().getSignature());
        constraintJson.addProperty("target", (target == null ? "" : target.toString()));


        String callbackType = getCallbackType(callbackOverrideMethod);
        constraintJson.addProperty("type", callbackType);

        if (callbackType.equals("activity") || callbackType.equals("service")) {
            constraintJson.addProperty("component", getComponentNameFromType(entryMethod.getDeclaringClass().getReference()));

        } else if (callbackType.equals("ui")) {
            Set<TypeReference> uiActivities = _uiActivityAnalysis.getActivityForUIHandler(callPath.getPath().get(0).getMethod(), callbackOverrideMethod);
            JsonArray activitiesJson = new JsonArray();

            if (uiActivities.isEmpty()) {
                activitiesJson.add(new JsonPrimitive(_manifestAnalysis.getMainActivityName()));
            }

            for (TypeReference uiActivity : uiActivities) {
                activitiesJson.add(new JsonPrimitive((getComponentNameFromType(uiActivity))));
            }

            constraintJson.add("activities", activitiesJson);

            if (_uiActivityAnalysis.isUILayoutDefinedHandler(entryMethod.getReference())) {
                constraintJson.add("listener", new JsonPrimitive("android.view.View$1"));
            } else {
                constraintJson.add("listener", new JsonPrimitive(getComponentNameFromType(entryMethod.getDeclaringClass().getReference())));
            }

            constraintJson.add("listenerMethod", new JsonPrimitive(entryMethod.getName().toString()));

            String uiCallbackType = getUICallbackType(callbackOverrideMethod);
            constraintJson.add("uiType", new JsonPrimitive(uiCallbackType));

            if (uiCallbackType.equals("onClick")) {
                if (callbackOverrideMethod.getDeclaringClass().getName().toString().equals("Landroid/content/DialogInterface$OnClickListener")) {
                    constraintJson.add("inDialog", new JsonPrimitive("true"));
                }
            }
        }

        // Write separate file containing constraints (if they exist and aren't redundant)
        if (constraints != null && !constraints.isTrue()) {
            Z3ConstraintGenerator z3Generator = new Z3ConstraintGenerator(constraints);

            try {
                PrintWriter writer = new PrintWriter(IntelliDroidAppAnalysis.Config.OutputDirectory + "/" + z3FileName, "UTF-8");
                File frameworkZ3File = new File(_frameworkZ3Dir + "/" + frameworkZ3FileName + ".py");

                if (frameworkZ3File.isFile()) {
                    String frameworkConstraintCode = new String(Files.toByteArray(frameworkZ3File), "UTF-8");
                    writer.print(frameworkConstraintCode);
                    writer.println("");
                }

                writer.println("# Entrypoint: " + callPath.getPath().get(0).getMethod().getSignature());
                writer.println("# Target: " + (target == null ? "" : target.toString()));
                writer.println("");
                writer.print(z3Generator.getZ3ConstraintCode());
                writer.close();
            } catch (Exception e) {
                System.err.println("Error: " + e.toString());
                e.printStackTrace();
            }

            // Add information about constraints
            constraintJson.addProperty("constraintsFile", z3FileName);
            constraintJson.add("variables", z3Generator.getVariableJsonObject());
            constraintJson.add("stringMap", z3Generator.getStringMapJsonObject());
            constraintJson.add("strings", z3Generator.getStringJsonObject());
        }

        return constraintJson;
    }

    //=========================================================================

    private String getCallbackType(MethodReference callbackMethod) {
        if (callbackMethod == null) {
            return "";
        }

        if (_uiActivityAnalysis.isUILayoutDefinedHandler(callbackMethod)) {
            return "ui";
        }

        String declaredClassName = callbackMethod.getDeclaringClass().getName().toString();

        if (declaredClassName.startsWith("Landroid/view")) {
            return "ui";
        }

        if (declaredClassName.startsWith("Landroid/content/DialogInterface$")) {
            return "ui";
        }

        if (declaredClassName.startsWith("Landroid/widget")) {
            return "ui";
        }

        if (declaredClassName.equals("Landroid/location/LocationListener")) {
            return "location";
        }

        if (declaredClassName.equals("Landroid/content/BroadcastReceiver")) {
            return "sms";
        }

        if (declaredClassName.equals("Landroid/telephony/PhoneStateListener")) {
            return "telephony";
        }

        if (declaredClassName.equals("Landroid/app/Activity")) {
            return "activity";
        }

        if (declaredClassName.equals("Landroid/app/Service")) {
            return "service";
        }

        // TODO: add a type for PreferenceActivity
        return "";
    }

    private String getUICallbackType(MethodReference callbackMethod) {
        return callbackMethod.getName().toString();
    }

    private String getFileFriendlyName(MethodReference method) {
        String fileName = method.getDeclaringClass().getName().toString();
        fileName += "." + method.getName().toString();
        fileName = fileName.replace("/", ".");
        return fileName;
    }

    private String getConstantValueString(SymbolTable symbolTable, int value) {
        if (symbolTable.isConstant(value)) {
            if (symbolTable.isIntegerConstant(value)) {
                return "" + symbolTable.getIntValue(value);
            } else if (symbolTable.isFloatConstant(value)) {
                return "" + symbolTable.getFloatValue(value);
            } else if (symbolTable.isDoubleConstant(value)) {
                return "" + symbolTable.getDoubleValue(value);
            } else if (symbolTable.isLongConstant(value)) {
                return "" + symbolTable.getLongValue(value);
            } else if (symbolTable.isStringConstant(value)) {
                //return new Expression("String<" + symbolTable.getStringValue(value) + ">", TypeReference.JavaLangString);
                return "" + symbolTable.getStringValue(value);
            } else if (symbolTable.isNullConstant(value)) {
                return "null";
            } else {
                return symbolTable.getConstantValue(value).toString();
            }
        }

        return "";
    }

    private String getComponentNameFromType(TypeReference type) {
        if (type.getName().getPackage() != null) {
            return type.getName().getPackage().toString().replace("/", ".") + "." + type.getName().getClassName().toString();
        } else {
            return type.getName().getClassName().toString();
        }
    }

    //private boolean containsUIDependency(final LinkedHashMap<CallPath, Predicate> eventChain) {
    //    for (CallPath eventPath : eventChain.keySet()) {
    //        if (containsUIEntrypoint(eventPath)) {
    //            return true;
    //        }
    //    }

    //    return false;
    //}

    //=========================================================================

    // For debugging purposes
    private void printControlFlowGraph(CGNode node) {
        SSACFG cfg = node.getIR().getControlFlowGraph();

        for (int i = 0; i < cfg.getNumberOfNodes(); i++) {
            System.out.println("BB " + i);
            SSACFG.BasicBlock bb = cfg.getBasicBlock(i);

            StringBuilder predString = new StringBuilder("predecessors: ");
            for (ISSABasicBlock predBlock : cfg.getNormalPredecessors(bb)) {
                predString.append(predBlock.getNumber());
                predString.append(" ");
            }
            System.out.println("    " + predString.toString());

            for (SSAInstruction instr : bb.getAllInstructions()) {
                if (instr != null) {
                    System.out.println("    " + instr.toString());
                }
            }

            StringBuilder succString = new StringBuilder("successors: ");
            for (ISSABasicBlock succBlock : cfg.getNormalSuccessors(bb)) {
                succString.append(succBlock.getNumber());
                succString.append(" ");
            }
            System.out.println("    " + succString.toString());
        }
    }

    private void printCallPath(int callPathID, CallPath callPath, Predicate constraints) {
        Output.printPathInfo("==============================================");
        Output.printPathInfo("Path (" + callPathID + ") to: " + callPath.getTargetCallSite().toString());

        MethodReference frameworkMethod = _entrypointAnalysis.getOverriddenFrameworkMethod(callPath.getPath().get(0).getMethod());
        Output.printPathInfo("    type: " + getCallbackType(frameworkMethod));

        for (CGNode node : callPath.getPath()) {
            Output.printPathInfo("    " + node.getMethod().getSignature());
        }

        Output.printConstraints(constraints);
    }

    private void printHeapPath(CallPath storeCallPath, Predicate storeConstraints, PointerKey pKey) {
        Output.printPathInfo("----------------------------------------------");
        Output.printPathInfo("Heap constraint: " + pKey.toString() + "; pKey: " + pKey.hashCode());

        // Print heap constraint info
        for (CGNode node : storeCallPath.getPath()) {
            Output.printPathInfo("    " + node.getMethod().getSignature());
        }

        Output.printConstraints(storeConstraints);
    }

    private void printSharedPrefPath(CallPath callPath, Predicate constraints, String key) {
        Output.printPathInfo("----------------------------------------------");
        Output.printPathInfo("SharedPreferences constraint: " + key);

        // Print constraint info
        for (CGNode node : callPath.getPath()) {
            Output.printPathInfo("    " + node.getMethod().getSignature());
        }

        Output.printConstraints(constraints);
    }

    private void printUIPath(CallPath callPath, Predicate constraints) {
        Output.printPathInfo("---------------------------");
        Output.printPathInfo("UI notification: " + callPath.getTargetCallSite().toString());

        for (CGNode pathNode : callPath.getPath()) {
            Output.printPathInfo("    " + pathNode.getMethod().getSignature());
        }
    }

    private void printCallbackRegistrationPath(CallPath callPath, Predicate constraints, IClass callbackClass) {
        Output.printPathInfo("----------------------------------------------");
        Output.printPathInfo("IPC constraint: " + callbackClass.getName().toString());

        // Print constraint info
        for (CGNode node : callPath.getPath()) {
            Output.printPathInfo("    " + node.getMethod().getSignature());
        }

        Output.printConstraints(constraints);
    }
}

