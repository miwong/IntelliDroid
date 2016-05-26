package intellidroid.appanalysis;

import com.ibm.wala.classLoader.*;
import com.ibm.wala.types.*;
import com.ibm.wala.ipa.cha.*;
import com.ibm.wala.ipa.callgraph.*;
import com.ibm.wala.ipa.callgraph.impl.*;
import com.ibm.wala.ipa.callgraph.propagation.*;
import com.ibm.wala.ssa.*;
import com.ibm.wala.util.strings.Atom;
import java.util.*;

class CallGraphInfoListener implements SSAPropagationCallGraphBuilder.BuilderListener {
    private IClassHierarchy _cha;
    private Map<String, Set<CGNode>> _targetMethodInvokes = new HashMap<String, Set<CGNode>>();
    private Map<PointerKey, Set<CGNode>> _heapStores = new HashMap<PointerKey, Set<CGNode>>();
    private Map<String, Set<CGNode>> _sharedPrefStores = new HashMap<String, Set<CGNode>>();
    private Map<String, CGNode> _sharedPrefUIStores = new HashMap<String, CGNode>();
    private Map<TypeReference, Set<CGNode>> _callbackRegistrations = new HashMap<TypeReference, Set<CGNode>>();

    public CallGraphInfoListener(IClassHierarchy cha) {
        _cha = cha;
    }

    // Used when creating call graphs iteratively to discover entry-points
    public void clear() {
        _targetMethodInvokes.clear();
        _heapStores.clear();
        _sharedPrefStores.clear();
        _sharedPrefUIStores.clear();
        _callbackRegistrations.clear();
    }

    public Set<String> getTargetMethods() {
        return _targetMethodInvokes.keySet();
    }

    public Set<CGNode> getTargetMethodInvokingNodes(String method) {
        return _targetMethodInvokes.get(method);
    }

    public Set<CGNode> getHeapStoreNodes(PointerKey pKey) {
        if (_heapStores.containsKey(pKey)) {
            return _heapStores.get(pKey);
        }

        return null;
    }

    public boolean hasSharedPrefStore(String key) {
        return _sharedPrefStores.containsKey(key);
    }

    public Set<CGNode> getSharedPrefStoreNodes(String key) {
        return _sharedPrefStores.get(key);
    }

    public boolean hasSharedPrefUIStore(String key) {
        return _sharedPrefUIStores.containsKey(key);
    }

    public CGNode getSharedPrefUIStore(String key) {
        return _sharedPrefUIStores.get(key);
    }

    public Set<CGNode> getCallbackRegistrations(TypeReference callbackClass) {
        return _callbackRegistrations.get(callbackClass);
    }

    //public Set<CGNode> getCallbackRegistrationNodes(IClass receiverClass) {
    //    if (_callbackRegistrations.containsKey(receiverClass)) {
    //        return _callbackRegistrations.get(receiverClass);
    //    }

    //    return null;
    //}

    @Override
    public void onPut(CGNode node, IField field, PointerKey[] pKeys) {
        //Output.debug("field: " + field);

        if (!node.getMethod().getDeclaringClass().getClassLoader().getReference().equals(ClassLoaderReference.Application)) {
            return;
        }

        if (pKeys != null) {
            for (PointerKey pKey : pKeys) {
                if (!_heapStores.containsKey(pKey)) {
                    _heapStores.put(pKey, new HashSet<CGNode>());
                }

                _heapStores.get(pKey).add(node);
            }
        }
    }

    @Override
    public void onInvoke(CGNode node, SSAAbstractInvokeInstruction invokeInstr) {
        if (!node.getMethod().getDeclaringClass().getClassLoader().getReference().equals(ClassLoaderReference.Application)) {
            return;
        }

        IMethod targetMethod = _cha.resolveMethod(invokeInstr.getDeclaredTarget());
        if (targetMethod == null) {
            return;
        }

        String targetMethodSignature = targetMethod.getSignature();
        //Output.debug("onInvoke: " + targetMethodSignature);

        if (IntelliDroidAppAnalysis.Config.TargetMethods.contains(targetMethodSignature)) {
            if (!_targetMethodInvokes.containsKey(targetMethodSignature)) {
                _targetMethodInvokes.put(targetMethodSignature, new HashSet<CGNode>());
            }

            _targetMethodInvokes.get(targetMethodSignature).add(node);

        } else if (AndroidMethods.isSharedPrefEditorMethod(targetMethod.getReference())) {
            if (node.getIR() == null) {
                return;
            }

            String sharedPrefKey = "";
            SymbolTable symbolTable = node.getIR().getSymbolTable();

            if (symbolTable.isStringConstant(invokeInstr.getUse(1))) {
                sharedPrefKey = symbolTable.getStringValue(invokeInstr.getUse(1));
            }

            if (!_sharedPrefStores.containsKey(sharedPrefKey)) {
                _sharedPrefStores.put(sharedPrefKey, new HashSet<CGNode>());
            }

            _sharedPrefStores.get(sharedPrefKey).add(node);

        //} else if (AndroidMethods.isCallbackRegistrationMethod(targetMethod.getReference())) {
        //    if (AndroidMethods.getCallbackRegistrationType(targetMethod.getReference()).equals(AndroidMethods.CallbackRegistrationType.BROADCAST)) {
        //        DefUse defUse = node.getDU();
        //        int receiverVal = invokeInstr.getUse(1);
        //        SSAInstruction defInstr = defUse.getDef(receiverVal);

        //        if (defInstr instanceof SSANewInstruction) {
        //            SSANewInstruction newInstr = (SSANewInstruction)defInstr;
        //            IClass receiverClass = _cha.lookupClass(newInstr.getConcreteType());

        //            if (receiverClass != null) {
        //                if (!_callbackRegistrations.containsKey(receiverClass)) {
        //                    _callbackRegistrations.put(receiverClass, new HashSet<CGNode>());
        //                }

        //                _callbackRegistrations.get(receiverClass).add(node);
        //            }
        //        }
        //    }
        //} else if (targetMethodSignature.startsWith("android.app.DialogFragment.show(")) {
        //    TypeReference dialogType = targetMethod.getDeclaringClass().getReference();
        //    if (!_callbackRegistrations.containsKey(dialogType)) {
        //        _callbackRegistrations.put(dialogType, new HashSet<CGNode>());
        //    }

        //    _callbackRegistrations.get(dialogType).add(node);

        } else {
            for (int i = 0; i < targetMethod.getNumberOfParameters(); i++) {
                TypeReference parameterType = targetMethod.getParameterType(i);

                if (AndroidMethods.isCallbackClass(parameterType)) {
                    if (!_callbackRegistrations.containsKey(parameterType)) {
                        _callbackRegistrations.put(parameterType, new HashSet<CGNode>());
                    }

                    _callbackRegistrations.get(parameterType).add(node);
                    break;
                }
            }
        }
    }

    public PointerKey getPointerKeyForFieldAccess(CGNode node, SSAFieldAccessInstruction instr, PointerAnalysis pa) {
        HeapModel heapModel = pa.getHeapModel();

        IClass declaringClass = _cha.lookupClass(instr.getDeclaredField().getDeclaringClass());
        if (declaringClass == null) {
            return null;
        }

        IField field = null;

        try {
            field = declaringClass.getField(Atom.findOrCreate(instr.getDeclaredField().getName().toString().getBytes()));
        } catch (IllegalStateException e) {
            for (IField classField : declaringClass.getAllFields()) {
                if (classField.getDeclaringClass().equals(declaringClass) && classField.getName().equals(instr.getDeclaredField().getName())) {
                    field = classField;
                    break;
                }
            }
        }

        if (field == null) {
            return null;
        }

        PointerKey fieldPKey = null;

        if (instr.isStatic()) {
            if (field != null) {
                fieldPKey = heapModel.getPointerKeyForStaticField(field);
            }
        } else {
            PointerKey classPKey = heapModel.getPointerKeyForLocal(node, instr.getRef());
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

        return fieldPKey;
    }
}

