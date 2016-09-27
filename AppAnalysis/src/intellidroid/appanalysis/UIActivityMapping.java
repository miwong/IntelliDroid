package intellidroid.appanalysis;

import com.ibm.wala.classLoader.*;
import com.ibm.wala.types.*;
import com.ibm.wala.ipa.cha.*;
import com.ibm.wala.ipa.callgraph.*;
import com.ibm.wala.ipa.callgraph.impl.*;
import com.ibm.wala.ipa.callgraph.propagation.*;
import com.ibm.wala.ssa.*;

import java.util.*;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.xpath.*;
import javax.xml.namespace.NamespaceContext;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import org.w3c.dom.Node;
import org.w3c.dom.Element;
import java.io.File;
import java.io.FilenameFilter;
import org.apache.commons.io.FilenameUtils;
import org.xml.sax.SAXParseException;

class UIActivityMapping {
    private final boolean DEBUG = false;

    private final IClassHierarchy _cha;
    private CallGraphInfoListener _callGraphInfoListener;
    private PointerAnalysis _pointerAnalysis;

    private Map<String, Set<String>> _handlerLayoutMap = new HashMap<String, Set<String>>();
    private Map<IMethod, Set<TypeReference>> _handlerActivityMap = new HashMap<IMethod, Set<TypeReference>>();

    public UIActivityMapping(IClassHierarchy cha) {
        if (DEBUG) {
            System.out.println("=================================================");
        }

        _cha = cha;

        try {
            getLayoutIDs();
            getLayoutHandlers();
            //mapHandlerToElements();
        } catch (Exception e) {
            System.err.println("Exception: " + e.toString());
            e.printStackTrace();
        }
    }

    public void setCallGraph(CallGraphInfoListener callGraphInfoListener, PointerAnalysis pointerAnalysis) {
        _callGraphInfoListener = callGraphInfoListener;
        _pointerAnalysis = pointerAnalysis;
    }

    private Map<Integer, String> getLayoutIDs() {
        Map<Integer, String> idLayoutMap = new HashMap<Integer, String>();
        try {
            File resourceFile = new File(IntelliDroidAppAnalysis.Config.AppDirectory + "/apk/res/values/public.xml");
            if (!resourceFile.exists()) {
                return null;
            }

            DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
            DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
            Document resourceXML = dBuilder.parse(resourceFile);
            resourceXML.getDocumentElement().normalize();

            //NodeList publicNodes = resourceXML.getElementsByTagName("public");
            XPathFactory xPathfactory = XPathFactory.newInstance();
            XPath xpath = xPathfactory.newXPath();
            XPathExpression expr = xpath.compile("/resources//public[@type=\"layout\"]");
            NodeList layoutNodes = (NodeList)expr.evaluate(resourceXML, XPathConstants.NODESET);

            if (layoutNodes != null) {
                for (int i = 0; i < layoutNodes.getLength(); i++) {
                    Node layoutNode = layoutNodes.item(i);
                    if (layoutNode.getNodeType() != Node.ELEMENT_NODE) {
                        continue;
                    }

                    Element layoutElement = (Element)layoutNode;
                    Integer layoutID = Integer.decode(layoutElement.getAttribute("id"));
                    String layoutName = layoutElement.getAttribute("name");

                    if (DEBUG) {
                    //    System.out.println("Layout: " + layoutName + "; id: " + layoutID);
                    }

                    idLayoutMap.put(layoutID, layoutName);
                }
            }

        } catch (Exception e) {
            System.err.println("Exception: " + e.toString());
            e.printStackTrace();
        }

        return idLayoutMap;
    }

    private void getLayoutHandlers() throws Exception {
        File resourceDir = new File(IntelliDroidAppAnalysis.Config.AppDirectory + "/apk/res/");
        if (!resourceDir.exists() || !resourceDir.isDirectory()) {
            return;
        }

        File[] layoutDirs = resourceDir.listFiles(new FilenameFilter() {
            public boolean accept(File dir, String name) {
                if (name.startsWith("layout")) {
                    return true;
                }

                return false;
            }
        });

        for (File layoutDir : layoutDirs) {
            File[] xmlFiles = layoutDir.listFiles(new FilenameFilter() {
                public boolean accept(File dir, String name) {
                    if (name.endsWith(".xml")) {
                        return true;
                    }

                    return false;
                }
            });

            for (File layoutXmlFile : xmlFiles) {
                processLayoutXml(layoutXmlFile);
            }
        }
    }

    private void processLayoutXml(File xmlFile) throws Exception {
        String layoutName = FilenameUtils.removeExtension(xmlFile.getName());

        DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
        DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
        try {
            Document resourceXML = dBuilder.parse(xmlFile);
        
            resourceXML.getDocumentElement().normalize();

            XPathFactory xPathfactory = XPathFactory.newInstance();
            XPath xpath = xPathfactory.newXPath();
            XPathExpression expr = xpath.compile("//@*[local-name()='id']/..");
            NodeList uiElementNodes = (NodeList)expr.evaluate(resourceXML, XPathConstants.NODESET);

            Collection<IClass> activitySubclasses = _cha.computeSubClasses(TypeReference.findOrCreate(ClassLoaderReference.Extension, "Landroid/app/Activity"));

            for (int i = 0; i < uiElementNodes.getLength(); i++) {
                Node uiElementNode = uiElementNodes.item(i);
                if (uiElementNode.getNodeType() != Node.ELEMENT_NODE) {
                    continue;
                }

                Element uiElement = (Element)uiElementNode;

                String elementName = uiElement.getTagName();
                //String id = uiElement.getAttribute("android:id");

                if (uiElement.hasAttribute("android:onClick")) {
                    String onClickMethodName = uiElement.getAttribute("android:onClick");

                    //System.out.println("Layout: " + layoutName);
                    //System.out.println("    onclick: " + onClickMethodName);

                    if (!_handlerLayoutMap.containsKey(onClickMethodName)) {
                        _handlerLayoutMap.put(onClickMethodName, new HashSet<String>());
                    }

                    _handlerLayoutMap.get(onClickMethodName).add(xmlFile.getName());

                    //for (IClass activitySubclass : activitySubclasses) {
                    //    if (!activitySubclass.getClassLoader().getReference().equals(ClassLoaderReference.Application)) {
                    //        continue;
                    //    }

                    //    for (IMethod declaredMethod : activitySubclass.getDeclaredMethods()) {
                    //        if (declaredMethod.getSelector().getName().toString().equals(onClickMethodName)) {
                    //            System.out.println("    activity: " + activitySubclass);
                    //        }
                    //    }
                    //}
                }
            }
        } catch (SAXParseException e) {
            // ignore invalid characters in the XML file
        } 
    }

    public Set<String> getUILayoutDefinedHandlers() {
        return _handlerLayoutMap.keySet();
    }

    public boolean isUILayoutDefinedHandler(MethodReference callbackMethod) {
        return _handlerLayoutMap.containsKey(callbackMethod.getName().toString());
    }

    public Set<TypeReference> getActivityForUIHandler(IMethod handler, MethodReference frameworkMethod) {
        if (_handlerActivityMap.containsKey(handler)) {
            return _handlerActivityMap.get(handler);
        } else {
            Set<TypeReference> handlerActivities = findActivityForUIHandler(handler, frameworkMethod);
            _handlerActivityMap.put(handler, handlerActivities);
            return handlerActivities;
        }
    }

    private Set<TypeReference> findActivityForUIHandler(IMethod handler, MethodReference frameworkMethod) {
        Set<TypeReference> handlerActivities = new HashSet<TypeReference>();

        IClass appHandlerClass = handler.getDeclaringClass();
        TypeReference frameworkCallbackClass = frameworkMethod.getDeclaringClass();

        Set<CGNode> regNodes = _callGraphInfoListener.getCallbackRegistrations(frameworkCallbackClass);
        if (regNodes == null) {
            return handlerActivities;
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
                MethodReference targetMethod = invokeInstr.getDeclaredTarget();

                boolean regMethodFound = false;
                TypeReference callbackType = null;

                for (int paramIndex = 0; paramIndex < targetMethod.getNumberOfParameters(); paramIndex++) {
                    if (targetMethod.getParameterType(paramIndex).getName().equals(frameworkCallbackClass.getName())) {
                        TypeReference registeredType = getHandlerTypeFromRegistration(regNode, invokeInstr, frameworkCallbackClass);

                        if (registeredType == null) {
                            //System.out.println("Error (UIActivityMapping): cannot find class type from registration");
                            break;
                        }

                        if (registeredType.equals(appHandlerClass.getReference())) {
                            regMethodFound = true;
                            break;
                        }
                    }
                }

                if (!regMethodFound) {
                    continue;
                }

                int uiElementVal = invokeInstr.getReceiver();
                TypeReference uiActivity= getUIElementActivity(regNode, uiElementVal);

                if (uiActivity != null) {
                    if (DEBUG) {
                        System.out.println("    activity: " + uiActivity);
                    }

                    //if (!_handlerActivityMap.containsKey(handler)) {
                    //    _handlerActivityMap.put(handler, new HashSet<TypeReference>());
                    //}

                    //_handlerActivityMap.get(handler).add(uiActivity);
                    handlerActivities.add(uiActivity);

                } else {
                    if (DEBUG) {
                        System.out.println("    < UI element activity not found >");
                    }
                }
            }
        }

        return handlerActivities;
    }

    //private void mapHandlerToElements() {
    //    for (TypeReference callbackClass : AndroidMethods.getCallbackClasses()) { 
    //        if (!isUICallbackClass(callbackClass)) {
    //            continue;
    //        }

    //        Set<CGNode> regNodes = _callGraphInfoListener.getCallbackRegistrations(callbackClass);
    //        if (regNodes == null) {
    //            continue;
    //        }

    //        for (CGNode regNode : regNodes) {
    //            IR ir = regNode.getIR();
    //            DefUse defUse = regNode.getDU();
    //            SSAInstruction[] instructions = ir.getInstructions();

    //            for (int instrIndex = 0; instrIndex < instructions.length; instrIndex++) {
    //                SSAInstruction instr = instructions[instrIndex];

    //                if (!(instr instanceof SSAAbstractInvokeInstruction)) {
    //                    continue;
    //                }

    //                SSAAbstractInvokeInstruction invokeInstr = (SSAAbstractInvokeInstruction)instr;
    //                MethodReference targetMethod = invokeInstr.getDeclaredTarget();

    //                boolean regMethodFound = false;
    //                TypeReference callbackType = null;
    //                //int callbackVal = -1;

    //                for (int paramIndex = 0; paramIndex < targetMethod.getNumberOfParameters(); paramIndex++) {
    //                    if (targetMethod.getParameterType(paramIndex).getName().equals(callbackClass.getName())) {
    //                        //callbackVal = invokeInstr.getUse(invokeInstr.isStatic() ? paramIndex : paramIndex + 1);
    //                        regMethodFound = true;
    //                        break;
    //                    }
    //                }

    //                //if (callbackVal < 0) {
    //                if (!regMethodFound) {
    //                    continue;
    //                }

    //                TypeReference registeredType = getHandlerTypeFromRegistration(regNode, invokeInstr, callbackClass);

    //                if (DEBUG) {
    //                    System.out.println("Callback: " + callbackClass);
    //                    //System.out.println("    registration: " + invokeInstr);
    //                    System.out.println("    handler type: " + registeredType);
    //                }

    //                int uiElementVal = invokeInstr.getReceiver();
    //                int uiElementID = getUIElementID(regNode, uiElementVal);

    //                if (uiElementID > 0) {
    //                    if (DEBUG) {
    //                        System.out.println("    ID: " + uiElementID);
    //                    }

    //                    if (!_listenerIDMap.containsKey(registeredType)) {
    //                        _listenerIDMap.put(registeredType, new HashSet<Integer>());
    //                    }

    //                    _listenerIDMap.get(registeredType).add(uiElementID);

    //                } else {
    //                    System.out.println("    < UI element ID not found >");
    //                }
    //            }
    //        }
    //    }
    //}

    public TypeReference getHandlerTypeFromRegistration(CGNode regNode, SSAAbstractInvokeInstruction regInstr, TypeReference callbackClass) {
        IR ir = regNode.getIR();
        DefUse defUse = regNode.getDU();
        IMethod targetMethod = _cha.resolveMethod(regInstr.getDeclaredTarget());

        int callbackVal = -1;

        for (int i = 0; i < targetMethod.getNumberOfParameters(); i++) {
            TypeReference parameterType = targetMethod.getParameterType(i);

            //if (AndroidMethods.isCallbackClass(parameterType)) {
            if (parameterType.getName().equals(callbackClass.getName())) {
                //callbackVal = regInstr.getUse(regInstr.isStatic() ? i : i + 1);
                callbackVal = regInstr.getUse(i);
                break;
            }
        }

        if (callbackVal < 0) {
            return null;
        }

        // Handle the cases where the callback class registered is the same as the registering class
        if (ir.getSymbolTable().getNumberOfParameters() > 0 && callbackVal == ir.getSymbolTable().getParameter(0)) {
            return regNode.getMethod().getDeclaringClass().getReference();
        }

        SSAInstruction callbackDefInstr = defUse.getDef(callbackVal);
        CGNode callbackRegNode = regNode;
        boolean iterate = false;

        do {
            iterate = false;

            //if (DEBUG) {
            //    System.out.println("def chain (registration): " + callbackRegNode);
            //}

            if (callbackDefInstr instanceof SSANewInstruction) {
                SSANewInstruction newInstr = (SSANewInstruction)callbackDefInstr;
                TypeReference regCallbackType = newInstr.getConcreteType();
                return regCallbackType;

            } else if (callbackDefInstr instanceof SSAGetInstruction) {
                SSAGetInstruction getInstr = (SSAGetInstruction)callbackDefInstr;
                PointerKey pKey = _callGraphInfoListener.getPointerKeyForFieldAccess(callbackRegNode, getInstr, _pointerAnalysis);
                Set<CGNode> storeNodes = _callGraphInfoListener.getHeapStoreNodes(pKey);

                if (storeNodes != null) {
                    for (CGNode storeNode : storeNodes) {
                        if (storeNode.equals(callbackRegNode) || storeNode.equals(regNode)) {
                            continue;
                        }

                        for (SSAInstruction nodeInstr : storeNode.getIR().getInstructions()) {
                            if (nodeInstr instanceof SSAPutInstruction) {
                                SSAPutInstruction putInstr = (SSAPutInstruction)nodeInstr;

                                PointerKey putPKey = _callGraphInfoListener.getPointerKeyForFieldAccess(storeNode, putInstr, _pointerAnalysis);
                                if (putPKey == null || !putPKey.equals(pKey)) {
                                    continue;
                                }

                                SSAInstruction nextDef = storeNode.getDU().getDef(putInstr.getVal());
                                if (nextDef != null && !nextDef.equals(callbackDefInstr)) {
                                    callbackDefInstr = nextDef;
                                    callbackRegNode = storeNode;
                                    iterate = true;
                                }
                            }
                        }
                    }
                }
            } else {
                if (callbackDefInstr != null && callbackDefInstr.getNumberOfUses() == 1) {
                    SSAInstruction nextDef = callbackRegNode.getDU().getDef(callbackDefInstr.getUse(0));
                    if (nextDef != null && !nextDef.equals(callbackDefInstr)) {
                        callbackDefInstr = nextDef;
                        iterate = true;
                    }
                }
            }
        } while (iterate && callbackRegNode != null && callbackDefInstr != null);

        return null;
    }

    private TypeReference getUIElementActivity(CGNode node, int uiElementVal) {
        DefUse defUse = node.getDU();

        SSAInstruction uiElementDef = defUse.getDef(uiElementVal);
        if (uiElementDef == null) {
            return null;
        }

        CGNode uiRegNode = node;
        boolean iterate = false;

        do {
            iterate = false;

            //if (DEBUG) {
            //    System.out.println("    def chain (activity): " + uiElementDef);
            //}

            if (uiElementDef instanceof SSAAbstractInvokeInstruction) {
                SSAAbstractInvokeInstruction uiInvokeInstr = (SSAAbstractInvokeInstruction)uiElementDef;
                IMethod targetMethod = _cha.resolveMethod(uiInvokeInstr.getDeclaredTarget());
                String targetMethodString = targetMethod.getSignature();
                String targetSelectorString = targetMethod.getSelector().toString();

                if (targetMethodString.equals("android.app.Activity.findViewById(I)Landroid/view/View;")) {
                //if (uiInvokeInstr.getDeclaredTarget().getSelector().toString().equals("findViewById(I)Landroid/view/View;")) {
                    if (uiInvokeInstr.getReceiver() == 1) {
                        TypeReference activity = uiRegNode.getMethod().getDeclaringClass().getReference();
                        return activity;
                    } else {
                        SSAInstruction nextDef = uiRegNode.getDU().getDef(uiInvokeInstr.getReceiver());
                        if (nextDef != null && !nextDef.equals(uiElementDef)) {
                            uiElementDef = nextDef;
                            iterate = true;
                        }
                    }
                    //SymbolTable regSymbolTable = uiRegNode.getIR().getSymbolTable();

                    //if (regSymbolTable.isIntegerConstant(uiInvokeInstr.getUse(1))) {
                    //    int elementID = regSymbolTable.getIntValue(uiInvokeInstr.getUse(1));
                    //    //System.out.println("    id: " + elementID);
                    //    return elementID;
                    //}
                //} else if (uiInvokeInstr.getDeclaredTarget().getDeclaringClass().toString().equals("Landroid/app/DialogFragment")) {
                //    // FragmentManager
                } else if (targetSelectorString.equals("findViewById(I)Landroid/view/View;") || 
                           targetSelectorString.startsWith("findViewWith")) {
                    // Handle view searches within a View object
                    SSAInstruction nextDef = uiRegNode.getDU().getDef(uiInvokeInstr.getUse(0));
                    if (nextDef != null && !nextDef.equals(uiElementDef)) {
                        uiElementDef = nextDef;
                        iterate = true;
                    }
                }

            } else if (uiElementDef instanceof SSAGetInstruction) {
                SSAGetInstruction getInstr = (SSAGetInstruction)uiElementDef;
                PointerKey pKey = _callGraphInfoListener.getPointerKeyForFieldAccess(uiRegNode, getInstr, _pointerAnalysis);

                if (pKey != null) {
                    Set<CGNode> storeNodes = _callGraphInfoListener.getHeapStoreNodes(pKey);

                    if (storeNodes != null) {
                        for (CGNode storeNode : storeNodes) {
                            //if (storeNode.equals(uiRegNode) || storeNode.equals(node)) {
                            //    continue;
                            //}

                            for (SSAInstruction nodeInstr : storeNode.getIR().getInstructions()) {
                                if (nodeInstr instanceof SSAPutInstruction) {
                                    SSAPutInstruction putInstr = (SSAPutInstruction)nodeInstr;

                                    PointerKey putPKey = _callGraphInfoListener.getPointerKeyForFieldAccess(storeNode, putInstr, _pointerAnalysis);
                                    if (putPKey == null || !putPKey.equals(pKey)) {
                                        continue;
                                    }

                                    SSAInstruction nextDef = storeNode.getDU().getDef(putInstr.getVal());
                                    if (nextDef != null && !nextDef.equals(uiElementDef)) {
                                        uiElementDef = nextDef;
                                        uiRegNode = storeNode;
                                        iterate = true;
                                        break;
                                    }
                                }
                            }
                        }
                    }
                }
            } else if (uiElementDef instanceof SSANewInstruction) {
                // TODO: handle custom UI elements (including AlertDialogs)
                Iterator<SSAInstruction> useInstrIter = uiRegNode.getDU().getUses(uiElementDef.getDef());

                while (useInstrIter.hasNext()) {
                    SSAInstruction useInstr = useInstrIter.next();
                    if (!(useInstr instanceof SSAAbstractInvokeInstruction)) {
                        continue;
                    }

                    SSAAbstractInvokeInstruction useInvokeInstr = (SSAAbstractInvokeInstruction)useInstr;
                    if (useInvokeInstr.getDeclaredTarget().getSelector().toString().startsWith("addView(Landroid/view/View;")) {
                        SSAInstruction nextDef = uiRegNode.getDU().getDef(useInvokeInstr.getUse(0));
                        if (nextDef != null && !nextDef.equals(uiElementDef)) {
                            uiElementDef = nextDef;
                            iterate = true;
                            break;
                        }
                    } else if (useInvokeInstr.getDeclaredTarget().getSignature().startsWith("android.app.AlertDialog.<init>(Landroid/content/Context;)")) {
                        if (useInvokeInstr.getUse(1) == 1) {
                            TypeReference activity = uiRegNode.getMethod().getDeclaringClass().getReference();
                            return activity;
                        } else {
                            SSAInstruction nextDef = uiRegNode.getDU().getDef(useInvokeInstr.getUse(1));
                            if (nextDef != null && !nextDef.equals(uiElementDef)) {
                                uiElementDef = nextDef;
                                iterate = true;
                                break;
                            }
                        }
                    }
                }
            } else {
                if (uiElementDef.getNumberOfUses() == 1) {
                    SSAInstruction nextDef = uiRegNode.getDU().getDef(uiElementDef.getUse(0));
                    if (nextDef != null && !nextDef.equals(uiElementDef)) {
                        uiElementDef = nextDef;
                        iterate = true;
                    }
                }
            }

        } while (iterate && uiElementDef != null && uiRegNode != null);

        return null;
    }

    private boolean isUICallbackClass(TypeReference callbackClass) {
        String className = callbackClass.getName().toString();

        if (className.startsWith("Landroid/view/View$") || className.startsWith("Landroid/content/DialogInterface$")) {
            return true;
        }

        return false;
    }
}

