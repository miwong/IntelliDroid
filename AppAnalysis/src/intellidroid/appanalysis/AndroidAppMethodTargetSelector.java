package intellidroid.appanalysis;

import com.ibm.wala.classLoader.*;
import com.ibm.wala.types.*;

import com.ibm.wala.ipa.callgraph.*;
import com.ibm.wala.ipa.callgraph.impl.*;
import com.ibm.wala.ipa.callgraph.propagation.*;
import com.ibm.wala.ipa.callgraph.propagation.cfa.*;
import com.ibm.wala.ipa.cha.ClassHierarchy;
import com.ibm.wala.ipa.cha.IClassHierarchy;
import com.ibm.wala.ssa.*;
import com.ibm.wala.util.intset.*;
import com.ibm.wala.shrikeBT.IInvokeInstruction;
import com.ibm.wala.ipa.summaries.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.xpath.*;
import javax.xml.namespace.NamespaceContext;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import org.w3c.dom.Node;
import org.w3c.dom.Element;
import java.io.File;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.DocumentBuilder;

class AndroidAppMethodTargetSelector implements MethodTargetSelector {
    private static final boolean DEBUG = false;

    private ManifestAnalysis _manifestAnalysis;
    private MethodTargetSelector _childSelector;
    private IClassHierarchy _cha;

    private IClass _handlerClass;
    private IClass _contextClass;
    private IClass _serviceClass;
    private IClass _intentServiceClass;
    private IClass _threadClass;
    private IClass _runnableClass;
    private IClass _asyncTaskClass;
    private IClass _timerClass;
    private IClass _methodClass;
    private IClass _executorServiceClass;
    private IClass _dialogFragmentClass;
    private IClass _preferenceActivityClass;
    //private IClass _broadcastReceiverClass;

    private enum MethodCase {
        NONE,
        START_SERVICE,
        START_ACTIVITY,
        THREAD_START,
        HANDLER_POST,
        RUNNABLE,
        EXECUTOR,
        ASYNC_TASK,
        TIMER_SCHEDULE,
        REFLECTION,
        DIALOG_FRAGMENT,
        MENUS
        //CALLBACK_REGISTRATION
    }

    static private final Map<Selector, MethodCase> _methodTargetCases = new HashMap<Selector, MethodCase>();

    static {
        _methodTargetCases.put(Selector.make("startService(Landroid/content/Intent;)Landroid/content/ComponentName;"), MethodCase.START_SERVICE);

        _methodTargetCases.put(Selector.make("startActivity(Landroid/content/Intent;)V"), MethodCase.START_ACTIVITY);
        _methodTargetCases.put(Selector.make("startActivity(Landroid/content/Intent;Landroid/os/Bundle;)V"), MethodCase.START_ACTIVITY);
        _methodTargetCases.put(Selector.make("startActivityForResult(Landroid/content/Intent;I)V"), MethodCase.START_ACTIVITY);
        _methodTargetCases.put(Selector.make("startActivityForResult(Landroid/content/Intent;ILandroid/os/Bundle;)V"), MethodCase.START_ACTIVITY);
        _methodTargetCases.put(Selector.make("startActivityIfNeeded(Landroid/content/Intent;I)Z"), MethodCase.START_ACTIVITY);
        _methodTargetCases.put(Selector.make("startActivityIfNeeded(Landroid/content/Intent;ILandroid/os/Bundle;)Z"), MethodCase.START_ACTIVITY);
        _methodTargetCases.put(Selector.make("startActivityFromChild(Landroid/app/Activity;Landroid/content/Intent;I)V"), MethodCase.START_ACTIVITY);
        _methodTargetCases.put(Selector.make("startActivityFromChild(Landroid/app/Activity;Landroid/content/Intent;ILandroid/os/Bundle;)V"), MethodCase.START_ACTIVITY);
        _methodTargetCases.put(Selector.make("startActivityFromFragment(Landroid/app/Fragment;Landroid/content/Intent;I)V"), MethodCase.START_ACTIVITY);
        _methodTargetCases.put(Selector.make("startActivityFromFragment(Landroid/app/Fragment;Landroid/content/Intent;ILandroid/os/Bundle;)V"), MethodCase.START_ACTIVITY);

        _methodTargetCases.put(Selector.make("start()V"), MethodCase.THREAD_START);

        _methodTargetCases.put(Selector.make("post(Ljava/lang/Runnable;)Z"), MethodCase.HANDLER_POST);
        _methodTargetCases.put(Selector.make("postAtTime(Ljava/lang/Runnable;J)Z"), MethodCase.HANDLER_POST);
        _methodTargetCases.put(Selector.make("postAtTime(Ljava/lang/Runnable;Ljava/lang/Object;J)Z"), MethodCase.HANDLER_POST);
        _methodTargetCases.put(Selector.make("postDelayed(Ljava/lang/Runnable;J)Z"), MethodCase.HANDLER_POST);
        _methodTargetCases.put(Selector.make("postAtFrontOfQueue(Ljava/lang/Runnable;)Z"), MethodCase.HANDLER_POST);

        _methodTargetCases.put(Selector.make("run()V"), MethodCase.RUNNABLE);

        _methodTargetCases.put(Selector.make("execute(Ljava/lang/Runnable;)V"), MethodCase.EXECUTOR);
        _methodTargetCases.put(Selector.make("submit(Ljava/lang/Runnable;)Ljava/util/concurrent/Future;"), MethodCase.EXECUTOR);
        _methodTargetCases.put(Selector.make("submit(Ljava/lang/Runnable;Ljava/lang/Object;)Ljava/util/concurrent/Future;"), MethodCase.EXECUTOR);
        _methodTargetCases.put(Selector.make("submit(Ljava/util/concurrent/Callable;)Ljava/util/concurrent/Future;"), MethodCase.EXECUTOR);

        _methodTargetCases.put(Selector.make("execute([Ljava/lang/Object;)Landroid/os/AsyncTask;"), MethodCase.ASYNC_TASK);

        _methodTargetCases.put(Selector.make("schedule(Ljava/util/TimerTask;J)V"), MethodCase.TIMER_SCHEDULE);
        _methodTargetCases.put(Selector.make("schedule(Ljava/util/TimerTask;Ljava/util/Date;)V"), MethodCase.TIMER_SCHEDULE);
        _methodTargetCases.put(Selector.make("schedule(Ljava/util/TimerTask;JJ)V"), MethodCase.TIMER_SCHEDULE);
        _methodTargetCases.put(Selector.make("schedule(Ljava/util/TimerTask;Ljava/util/Date;J)V"), MethodCase.TIMER_SCHEDULE);
        _methodTargetCases.put(Selector.make("scheduleAtFixedRate(Ljava/util/TimerTask;JJ)V"), MethodCase.TIMER_SCHEDULE);
        _methodTargetCases.put(Selector.make("scheduleAtFixedRate(Ljava/util/TimerTask;Ljava/util/Date;J)V"), MethodCase.TIMER_SCHEDULE);

        _methodTargetCases.put(Selector.make("invoke(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;"), MethodCase.REFLECTION);

        _methodTargetCases.put(Selector.make("show(Landroid/app/FragmentManager;Ljava/lang/String;)V"), MethodCase.DIALOG_FRAGMENT);
        _methodTargetCases.put(Selector.make("show(Landroid/app/FragmentTransaction;Ljava/lang/String;)I"), MethodCase.DIALOG_FRAGMENT);
        _methodTargetCases.put(Selector.make("onCreateDialog(Landroid/os/Bundle;)Landroid/app/Dialog;"), MethodCase.DIALOG_FRAGMENT);

        _methodTargetCases.put(Selector.make("loadHeadersFromResource(ILjava/util/List;)V"), MethodCase.MENUS);
        
        //_methodTargetCases.put("registerReceiver(Landroid/content/BroadcastReceiver;Landroid/content/IntentFilter;)Landroid/content/Intent;", MethodCase.CALLBACK_REGISTRATION);
        //_methodTargetCases.put("registerReceiver(Landroid/content/BroadcastReceiver;Landroid/content/IntentFilter;Ljava/lang/String;Landroid/os/Handler;)Landroid/content/Intent;", MethodCase.CALLBACK_REGISTRATION);
    }

    public AndroidAppMethodTargetSelector(MethodTargetSelector childSelector, IClassHierarchy cha, ManifestAnalysis manifestAnalysis) {
        _childSelector = childSelector;
        _cha = cha;
        _manifestAnalysis = manifestAnalysis;

        _handlerClass = cha.lookupClass(TypeReference.findOrCreate(ClassLoaderReference.Extension, "Landroid/os/Handler"));
        _contextClass = cha.lookupClass(TypeReference.findOrCreate(ClassLoaderReference.Extension, "Landroid/content/Context"));
        _serviceClass = cha.lookupClass(TypeReference.findOrCreate(ClassLoaderReference.Extension, "Landroid/app/Service"));
        _intentServiceClass = cha.lookupClass(TypeReference.findOrCreate(ClassLoaderReference.Extension, "Landroid/app/IntentService"));
        _threadClass = cha.lookupClass(TypeReference.findOrCreate(ClassLoaderReference.Extension, "Ljava/lang/Thread"));
        _runnableClass = cha.lookupClass(TypeReference.findOrCreate(ClassLoaderReference.Extension, "Ljava/lang/Runnable"));
        _asyncTaskClass = cha.lookupClass(TypeReference.findOrCreate(ClassLoaderReference.Extension, "Landroid/os/AsyncTask"));
        _timerClass = cha.lookupClass(TypeReference.findOrCreate(ClassLoaderReference.Extension, "Ljava/util/Timer"));
        _methodClass = cha.lookupClass(TypeReference.findOrCreate(ClassLoaderReference.Extension, "Ljava/lang/reflect/Method"));
        _executorServiceClass = cha.lookupClass(TypeReference.findOrCreate(ClassLoaderReference.Extension, "Ljava/util/concurrent/ExecutorService"));
        _dialogFragmentClass = cha.lookupClass(TypeReference.findOrCreate(ClassLoaderReference.Extension, "Landroid/app/DialogFragment"));
        //_preferenceActivityClass = cha.lookupClass(TypeReference.findOrCreate(ClassLoaderReference.Extension, "Landroid/preference/PreferenceActivity"));
        //_broadcastReceiverClass = cha.lookupClass(TypeReference.findOrCreate(ClassLoaderReference.Extension, "Landroid/content/BroadcastReceiver"));
    }

    @Override
    public IMethod getCalleeTarget(CGNode caller, CallSiteReference site, IClass receiver) {
        Output.debug(DEBUG, "getCalleeTarget: " + caller.getMethod().getSignature());
        Output.debug(DEBUG, "    site: " + site.toString());
        if (receiver != null) {
            Output.debug(DEBUG, "    receiver: " + receiver.getName().toString());
        }

        IMethod callerMethod = caller.getMethod();
        IMethod declaredMethod = _cha.resolveMethod(site.getDeclaredTarget());

        if (declaredMethod == null) {
            Output.debug(DEBUG, "    < could not resolve declared method >");
            return _childSelector.getCalleeTarget(caller, site, receiver);
        }

        IClass declaredClass = (receiver == null) ? declaredMethod.getDeclaringClass() : receiver;
        MethodCase declaredMethodCase = MethodCase.NONE;
    
        if (_methodTargetCases.containsKey(declaredMethod.getSelector())) {
            declaredMethodCase = _methodTargetCases.get(declaredMethod.getSelector());
        } else {
            IMethod targetMethod =  _childSelector.getCalleeTarget(caller, site, receiver);

            if (targetMethod != null) {
                Output.debug(DEBUG, "    Default: " + targetMethod.getSignature());
            }

            return targetMethod;
        }

        switch (declaredMethodCase) {
            case START_SERVICE: {
                if (_cha.isSubclassOf(declaredClass, _contextClass) || declaredClass.equals(_contextClass)) {
                    IMethod targetMethod = getTargetMethodForStartService(caller, site);

                    if (targetMethod != null) {
                        Output.debug(DEBUG, "    Intent: " + targetMethod.getSignature());
                        return targetMethod;
                    }
                }
                break;
            }
            case START_ACTIVITY: {
                if (_cha.isSubclassOf(declaredClass, _contextClass) || declaredClass.equals(_contextClass)) {
                    IR ir = caller.getIR();
                    DefUse defUse = caller.getDU();
                    SymbolTable symbolTable = ir.getSymbolTable();

                    SSAAbstractInvokeInstruction callInstr = (SSAAbstractInvokeInstruction)ir.getPEI(new ProgramCounter(site.getProgramCounter()));
                    int intentVal = callInstr.getUse(1);
                    if (!AndroidMethods.isIntentClass(declaredMethod.getParameterType(1)) && callInstr.getNumberOfUses() > 2) {
                        intentVal = callInstr.getUse(2);
                    }

                    int intentTargetVal = -1;
                    Iterator<SSAInstruction> intentUseIter = defUse.getUses(intentVal);

                    while (intentUseIter.hasNext()) {
                        SSAInstruction intentUseInstr = intentUseIter.next();

                        if (intentUseInstr instanceof SSAAbstractInvokeInstruction) {
                            SSAAbstractInvokeInstruction invokeInstr = (SSAAbstractInvokeInstruction)intentUseInstr;
                            String invokeTargetString = invokeInstr.getDeclaredTarget().getSignature();

                            if (invokeTargetString.startsWith("android.content.Intent.putExtra(Ljava/lang/String;")) {
                                if (symbolTable.isStringConstant(invokeInstr.getUse(1))) {
                                    String key = symbolTable.getStringValue(invokeInstr.getUse(1));

                                    if (key.equals(":android:show_fragment")) {
                                        IMethod targetMethod = getTargetMethodForFragment(caller, site, invokeInstr.getUse(2));

                                        if (targetMethod != null) {
                                            Output.debug(DEBUG, "    Fragment: " + targetMethod.getSignature());
                                            return targetMethod;
                                        }
                                    }
                                }
                            } else if (invokeTargetString.startsWith("android.content.Intent.setClassName(")) {
                                IMethod targetMethod = getTargetMethodForFragment(caller, site, invokeInstr.getUse(2));

                                if (targetMethod != null) {
                                    Output.debug(DEBUG, "    Fragment: " + targetMethod.getSignature());
                                    return targetMethod;
                                }
                            }
                        }
                    }
                }

                break;
            }
            case THREAD_START: {
                if (_cha.isSubclassOf(declaredClass, _threadClass)) {
                    IMethod targetMethod;

                    if (declaredClass.equals(_threadClass)) {
                        //targetMethod = getTargetMethodForThreadRunnable(caller, site);
                        targetMethod = getTargetMethodForThreadStart(caller, site);
                    } else {
                        targetMethod = declaredClass.getMethod(Selector.make("run()V"));
                    }

                    if (targetMethod != null) {
                        Output.debug(DEBUG, "    Thread: " + targetMethod.getSignature());
                        return targetMethod;
                    }
                }

                break;
            }
            case HANDLER_POST: {
                if (_cha.isSubclassOf(declaredClass, _handlerClass)) {
                    IMethod targetMethod = getTargetMethodForHandlerPost(caller, site, declaredMethod);

                    if (targetMethod != null) {
                        Output.debug(DEBUG, "    Handler.post: " + targetMethod.getSignature());
                        return targetMethod;
                    }
                }

                break;
            }
            case RUNNABLE: {
                if (_cha.implementsInterface(declaredClass, _runnableClass)) {
                    Output.debug(DEBUG, "    Runnable.run: " + declaredMethod.getSignature());
                    return declaredMethod;
                }

                break;
            }
            case EXECUTOR: {
                if (_cha.implementsInterface(declaredClass, _executorServiceClass)) {
                    IMethod targetMethod = getTargetMethodForHandlerPost(caller, site, declaredMethod);

                    if (targetMethod != null) {
                        Output.debug(DEBUG, "    ExecutorService: " + targetMethod.getSignature());
                        return targetMethod;
                    }
                }

                break;
            }
            case ASYNC_TASK: {
                if (_cha.isSubclassOf(declaredClass, _asyncTaskClass)) {
                    IMethod targetMethod = getTargetMethodForAsyncTask(declaredClass);
                    Output.debug(DEBUG, "    AsyncTask: " + targetMethod.getSignature());
                    return targetMethod;
                }

                break;
            }
            case TIMER_SCHEDULE: {
                if (_cha.isSubclassOf(declaredClass, _timerClass)) {
                    IR ir = caller.getIR();
                    DefUse defUse = caller.getDU();
                    SymbolTable symbolTable = ir.getSymbolTable();

                    SSAAbstractInvokeInstruction callInstr = (SSAAbstractInvokeInstruction)ir.getPEI(new ProgramCounter(site.getProgramCounter()));
                    int timerTaskVal = callInstr.getUse(1);

                    SSAInstruction defInstr = defUse.getDef(timerTaskVal);
                    if (defInstr instanceof SSANewInstruction) {
                        SSANewInstruction newInstr = (SSANewInstruction)defInstr;

                        IClass timerTaskClass = _cha.lookupClass(TypeReference.findOrCreate(ClassLoaderReference.Application, newInstr.getConcreteType().getName().toString()));

                        if (timerTaskClass != null) {
                            IMethod runMethod = timerTaskClass.getMethod(Selector.make("run()V"));
                            
                            if (runMethod != null) {
                                Output.debug(DEBUG, "    TimerTask: " + runMethod.getSignature());
                                return runMethod;
                            }
                        }
                    }
                }

                break;
            }
            case REFLECTION: {
                if (_cha.isSubclassOf(declaredClass, _methodClass)) {
                    IMethod targetMethod = getTargetMethodForReflection(caller, site);

                    if (targetMethod != null) {
                        Output.debug(DEBUG, "    Reflection: " + targetMethod.getSignature());
                        return targetMethod;
                    }
                }

                break;
            }
            case DIALOG_FRAGMENT: {
                if (_cha.isSubclassOf(declaredClass, _dialogFragmentClass)) {
                    SSAAbstractInvokeInstruction callInstr = (SSAAbstractInvokeInstruction)caller.getIR().getPEI(new ProgramCounter(site.getProgramCounter()));
                    IMethod targetMethod = getTargetMethodForDialogFragment(caller, site, callInstr.getUse(0));

                    if (targetMethod != null) {
                        Output.debug(DEBUG, "    Dialog fragment: " + targetMethod.getSignature());
                        return targetMethod;
                    }
                }

                break;
            }
            case NONE:
            default: {
                break; 
            }
        }

        return _childSelector.getCalleeTarget(caller, site, receiver);
    }

    private IMethod getTargetMethodForStartService(CGNode caller, CallSiteReference site) {
        IR ir = caller.getIR();
        DefUse defUse = caller.getDU();
        SymbolTable symbolTable = ir.getSymbolTable();

        SSAAbstractInvokeInstruction callInstr = (SSAAbstractInvokeInstruction)ir.getPEI(new ProgramCounter(site.getProgramCounter()));
        int intentVal = callInstr.getUse(1);

        int intentTargetVal = -1;
        Iterator<SSAInstruction> intentUseIter = defUse.getUses(intentVal);

        while (intentUseIter.hasNext()) {
            SSAInstruction intentUseInstr = intentUseIter.next();

            if (intentUseInstr instanceof SSAAbstractInvokeInstruction) {
                SSAAbstractInvokeInstruction invokeInstr = (SSAAbstractInvokeInstruction)intentUseInstr;
                String invokeTargetString = invokeInstr.getDeclaredTarget().getSignature();

                if (invokeTargetString.startsWith("android.content.Intent.<init>(Ljava/lang/String;")) {
                    intentTargetVal = invokeInstr.getUse(1);
                }

                if (invokeTargetString.equals("android.content.Intent.setAction(Ljava/lang/String;)Landroid/content/Intent;")) {
                    intentTargetVal = invokeInstr.getUse(1);
                }

                if (invokeTargetString.startsWith("android.content.Intent.<init>") && invokeTargetString.endsWith("Ljava/lang/Class;)V")) {
                    int intentClassVal = invokeInstr.getUse(invokeInstr.getNumberOfUses() - 1);
                    SSAInstruction defInstr = defUse.getDef(intentClassVal);
                    IMethod targetMethod = getTargetMethodForServiceDefinition(defInstr);

                    if (targetMethod != null) {
                        return targetMethod;
                    }
                }

                if (invokeTargetString.equals("android.content.Intent.setClass(Landroid/content/Context;Ljava/lang/Class;)Landroid/content/Intent;")) {
                    int intentClassVal = invokeInstr.getUse(2);
                    SSAInstruction defInstr = defUse.getDef(intentClassVal);
                    IMethod targetMethod = getTargetMethodForServiceDefinition(defInstr);

                    if (targetMethod != null) {
                        return targetMethod;
                    }
                }
            }
        }

        if (intentTargetVal >= 0) {
            if (ir.getSymbolTable().isStringConstant(intentTargetVal)) {
                String actionString = ir.getSymbolTable().getStringValue(intentTargetVal);

                String serviceString = _manifestAnalysis.getServiceForActionString(actionString);

                if (serviceString != null) {
                    serviceString = serviceString.replace(".", "/");
                    serviceString = "L" + serviceString;

                    IClass serviceClass = _cha.lookupClass(TypeReference.findOrCreate(ClassLoaderReference.Application, serviceString));

                    if (serviceClass != null) {
                        IMethod targetMethod = getTargetMethodForService(serviceClass);

                        if (targetMethod != null) {
                            return targetMethod;
                        }
                    }
                }
            }
        }

        return null;
    }

    private IMethod getTargetMethodForServiceDefinition(SSAInstruction classDefInstr) {
        if (classDefInstr instanceof SSALoadMetadataInstruction) {
            SSALoadMetadataInstruction loadInstr = (SSALoadMetadataInstruction)classDefInstr;
            IClass intentClass = _cha.lookupClass(TypeReference.findOrCreate(ClassLoaderReference.Application, ((TypeReference)loadInstr.getToken()).getName().toString()));

            return getTargetMethodForService(intentClass);
        }

        return null;
    }

    private IMethod getTargetMethodForService(IClass serviceClass) {
        if (serviceClass != null) {
            if (_cha.isSubclassOf(serviceClass, _intentServiceClass)) {
                IMethod onHandleIntentMethod = serviceClass.getMethod(Selector.make("onHandleIntent(Landroid/content/Intent;)V"));
                return onHandleIntentMethod;

            } else if (_cha.isSubclassOf(serviceClass, _serviceClass)) {
                IMethod onStartCommandMethod = serviceClass.getMethod(Selector.make("onStartCommand(Landroid/content/Intent;II)I"));

                // For backward compatibility (old Android versions use onStart instead of onStartCommand)
                if (!onStartCommandMethod.getDeclaringClass().equals(serviceClass)) {
                    onStartCommandMethod = serviceClass.getMethod(Selector.make("onStart(Landroid/content/Intent;I)V"));
                }

                return onStartCommandMethod;
            }
        }

        return null;
    }

    // 1) Check local def-use for instantiation of Runnable subclass
    // 2) If Runnable obtained from heap, check field type for Runnable subclass
    // 3) Otherwise, get list of inner Runnable subclasses and create edge to all of them using a fake method

    private List<IMethod> getTargetMethodsForRunnable(CGNode caller, CallSiteReference site, int runnableVal) {
        IR ir = caller.getIR();
        DefUse defUse = caller.getDU();

        SSAInstruction defInstr = defUse.getDef(runnableVal);

        if (defInstr instanceof SSANewInstruction) {
            SSANewInstruction newInstr = (SSANewInstruction)defInstr;
            IClass newClass = _cha.lookupClass(newInstr.getConcreteType());

            if (newClass != null) {
                if (newClass.equals(_threadClass)) {
                    // start() is called on a Thread object.  Find the runnable object it uses, if possible
                    Iterator<SSAInstruction> threadUseInstrIter = defUse.getUses(runnableVal);

                    while (threadUseInstrIter.hasNext()) {
                        SSAInstruction useInstr = threadUseInstrIter.next();
                        if (!(useInstr instanceof SSAAbstractInvokeInstruction)) {
                            continue;
                        }

                        SSAAbstractInvokeInstruction initInvokeInstr = (SSAAbstractInvokeInstruction)useInstr;
                        MethodReference initMethod = initInvokeInstr.getDeclaredTarget();
                        if (!initMethod.isInit()) {
                            continue;
                        }

                        for (int i = 0; i < initMethod.getNumberOfParameters(); i++) {
                            // Note: parameter list does not include "this", but list of instruction uses does
                            if (initMethod.getParameterType(i).equals(_runnableClass.getReference())) {
                                return getTargetMethodsForRunnable(caller, initInvokeInstr.getCallSite(), initInvokeInstr.getUse(i + 1));
                            }
                        }
                    }

                } else if (_cha.implementsInterface(newClass, _runnableClass)) {
                    IClass runnableSubclass = newClass;
                    IMethod runMethod = runnableSubclass.getMethod(Selector.make("run()V"));
                    List<IMethod> runnableMethods = new ArrayList<IMethod>();
                    runnableMethods.add(runMethod);
                    return runnableMethods;
                }
            }
        } else if (defInstr instanceof SSAGetInstruction) {
            SSAGetInstruction getInstr = (SSAGetInstruction)defInstr;
            IClass runnableSubclass = _cha.lookupClass(getInstr.getDeclaredFieldType());

            if (runnableSubclass != null && _cha.implementsInterface(runnableSubclass, _runnableClass) && !runnableSubclass.equals(_runnableClass)) {
                // Runnable subclass type is known exactly
                //if (_cha.isSubclassOf(runnableSubclass, _runnableClass) && !runnableSubclass.equals(_runnableClass)) {
                IMethod runMethod = runnableSubclass.getMethod(Selector.make("run()V"));
                List<IMethod> runnableMethods = new ArrayList<IMethod>();
                runnableMethods.add(runMethod);
                return runnableMethods;
                //}
            }
        }

        // Use inner class heuristic to find possible Runnable matches
        IClass callerClass = caller.getMethod().getDeclaringClass();
        List<IMethod> runnableMethods = new ArrayList<IMethod>();
        Set<IClass> runnableClasses = _cha.getImplementors(_runnableClass.getReference());

        for (IClass subClass : runnableClasses) {
            if (subClass.equals(_runnableClass)) {
                continue;
            }

            if (subClass.getName().toString().contains(callerClass.getName().toString())) {
                IMethod runMethod = subClass.getMethod(Selector.make("run()V"));
                runnableMethods.add(runMethod);
            }
        }

        if (!runnableMethods.isEmpty()) {
            return runnableMethods;
        }

        return null;
    }

    private IMethod getTargetMethodForThreadStart(CGNode caller, CallSiteReference site) {
        IClass callerClass = caller.getMethod().getDeclaringClass();
        SSAAbstractInvokeInstruction callInstr = (SSAAbstractInvokeInstruction)caller.getIR().getPEI(new ProgramCounter(site.getProgramCounter()));

        List<IMethod> runnableMethods = getTargetMethodsForRunnable(caller, site, callInstr.getUse(0));
        if (runnableMethods == null) {
            return null;
        }

        if (runnableMethods.size() == 1) {
            return runnableMethods.get(0);
        }

        IClass runnableSubclass = runnableMethods.get(0).getDeclaringClass();

        IMethod fakeMethod = runnableSubclass.getMethod(Selector.make("<fakeStart>()V"));
        if (fakeMethod != null) {
            return fakeMethod;
        }

        MethodReference fakeMethodRef = MethodReference.findOrCreate(runnableSubclass.getReference(), Selector.make("<fakeStart>()V"));
        MethodSummary fakeMethodSummary = new MethodSummary(fakeMethodRef);

        for (IMethod runMethod : runnableMethods) {
            fakeMethodSummary.addStatement(Language.JAVA.instructionFactory().InvokeInstruction(-1, new int[] {1}, 4,
                CallSiteReference.make(fakeMethodSummary.getNextProgramCounter(), runMethod.getReference(), IInvokeInstruction.Dispatch.VIRTUAL)));
        }

        fakeMethodSummary.addStatement(Language.JAVA.instructionFactory().ReturnInstruction());

        fakeMethod = new SummarizedMethod(fakeMethodRef, fakeMethodSummary, runnableSubclass);
        return fakeMethod;
    }

    private IMethod getTargetMethodForHandlerPost(CGNode caller, CallSiteReference site, IMethod postMethod) {
        String paramSignature = postMethod.getSelector().toString();
        paramSignature = paramSignature.substring(paramSignature.indexOf("("));

        SSAAbstractInvokeInstruction callInstr = (SSAAbstractInvokeInstruction)caller.getIR().getPEI(new ProgramCounter(site.getProgramCounter()));
        List<IMethod> runnableMethods = getTargetMethodsForRunnable(caller, site, callInstr.getUse(1));
        if (runnableMethods == null) {
            return null;
        }

        IClass runnableClass = runnableMethods.get(0).getDeclaringClass();

        MethodReference fakeMethodRef = MethodReference.findOrCreate(runnableClass.getReference(), Selector.make("<fakePost>" + paramSignature));
        MethodSummary fakeMethodSummary = new MethodSummary(fakeMethodRef);

        //fakeMethodSummary.addStatement(Language.JAVA.instructionFactory().InvokeInstruction(-1, new int[] {2}, 5,
        //    CallSiteReference.make(fakeMethodSummary.getNextProgramCounter(), runMethod.getReference(), IInvokeInstruction.Dispatch.VIRTUAL)));

        for (IMethod runMethod : runnableMethods) {
            fakeMethodSummary.addStatement(Language.JAVA.instructionFactory().InvokeInstruction(-1, new int[] {2}, 5,
                CallSiteReference.make(fakeMethodSummary.getNextProgramCounter(), runMethod.getReference(), IInvokeInstruction.Dispatch.VIRTUAL)));
        }

        if (postMethod.getReturnType() != TypeReference.Void) {
            fakeMethodSummary.addConstant(6, new ConstantValue(0));
            fakeMethodSummary.addStatement(Language.JAVA.instructionFactory().ReturnInstruction(6, true));
        } else {
            fakeMethodSummary.addStatement(Language.JAVA.instructionFactory().ReturnInstruction());
        }

        IMethod fakeMethod = new SummarizedMethod(fakeMethodRef, fakeMethodSummary, runnableClass);
        return fakeMethod;
    }

    private IMethod getTargetMethodForFragment(CGNode caller, CallSiteReference site, int fragmentVal) {
        IR ir = caller.getIR();
        SymbolTable symbolTable = ir.getSymbolTable();
        String fragmentName = null;

        if (symbolTable.isStringConstant(fragmentVal)) {
            fragmentName = symbolTable.getStringValue(fragmentVal);
        } else {
            DefUse defUse = caller.getDU();
            SSAInstruction fragmentNameDef = defUse.getDef(fragmentVal);

            while (fragmentNameDef != null) {
                if (fragmentNameDef instanceof SSALoadMetadataInstruction) {
                    SSALoadMetadataInstruction loadInstr = (SSALoadMetadataInstruction)fragmentNameDef;
                    fragmentName = ((TypeReference)loadInstr.getToken()).getName().toString();
                    break;
                } else {
                    if (fragmentNameDef.getNumberOfUses() > 0) {
                        fragmentNameDef = defUse.getDef(fragmentNameDef.getUse(0));
                    }
                }
            }
        }

        if (fragmentName != null) {
            IClass fragmentClass = _cha.lookupClass(TypeReference.findOrCreate(ClassLoaderReference.Application, fragmentName));

            if (fragmentClass != null) {
                IMethod fragmentOnCreateMethod = fragmentClass.getMethod(Selector.make("onCreate(Landroid/os/Bundle;)V"));
                return fragmentOnCreateMethod;
            }
        }

        return null;
    }

    private IMethod getTargetMethodForAsyncTask(IClass asyncTaskClass) {
        MethodReference fakeMethodRef = MethodReference.findOrCreate(asyncTaskClass.getReference(), Selector.make("<fakeExecute>([Ljava/lang/Object;)V"));
        MethodSummary fakeMethodSummary = new MethodSummary(fakeMethodRef);

        IMethod onPreExecuteMethod = asyncTaskClass.getMethod(Selector.make("onPreExecute()V"));
        IMethod doInBackgroundMethod = asyncTaskClass.getMethod(Selector.make("doInBackground([Ljava/lang/Object;)Ljava/lang/Object;"));
        IMethod onPostExecuteMethod = asyncTaskClass.getMethod(Selector.make("onPostExecute(Ljava/lang/Object;)V"));
        IMethod onProgressUpdateMethod = asyncTaskClass.getMethod(Selector.make("onProgressUpdate([Ljava/lang/Object;)V"));

        fakeMethodSummary.addStatement(Language.JAVA.instructionFactory().InvokeInstruction(-1, new int[] {1}, 4, 
            CallSiteReference.make(fakeMethodSummary.getNextProgramCounter(), onPreExecuteMethod.getReference(), IInvokeInstruction.Dispatch.VIRTUAL)));

        fakeMethodSummary.addStatement(Language.JAVA.instructionFactory().InvokeInstruction(3, new int[] {1, 2}, 4, 
            CallSiteReference.make(fakeMethodSummary.getNextProgramCounter(), doInBackgroundMethod.getReference(), IInvokeInstruction.Dispatch.VIRTUAL)));

        fakeMethodSummary.addStatement(Language.JAVA.instructionFactory().InvokeInstruction(-1, new int[] {1, 2}, 4, 
            CallSiteReference.make(fakeMethodSummary.getNextProgramCounter(), onProgressUpdateMethod.getReference(), IInvokeInstruction.Dispatch.VIRTUAL)));

        fakeMethodSummary.addStatement(Language.JAVA.instructionFactory().InvokeInstruction(-1, new int[] {1, 2}, 4, 
            CallSiteReference.make(fakeMethodSummary.getNextProgramCounter(), onPostExecuteMethod.getReference(), IInvokeInstruction.Dispatch.VIRTUAL)));

        fakeMethodSummary.addStatement(Language.JAVA.instructionFactory().ReturnInstruction());

        IMethod fakeMethod = new SummarizedMethod(fakeMethodRef, fakeMethodSummary, asyncTaskClass);

        return fakeMethod;
    }

    private IMethod getTargetMethodForReflection(CGNode caller, CallSiteReference site) {
        IR ir = caller.getIR();
        DefUse defUse = caller.getDU();
        SymbolTable symbolTable = ir.getSymbolTable();

        SSAAbstractInvokeInstruction callInstr = (SSAAbstractInvokeInstruction)ir.getPEI(new ProgramCounter(site.getProgramCounter()));
        int methodVal = callInstr.getUse(0);

        SSAInstruction methodDefInstr = defUse.getDef(methodVal);
        if (!(methodDefInstr instanceof SSAAbstractInvokeInstruction)) {
            return null;
        }

        SSAAbstractInvokeInstruction methodInvokeInstr = (SSAAbstractInvokeInstruction)methodDefInstr;
        if (!methodInvokeInstr.getDeclaredTarget().getSignature().equals("java.lang.Class.getMethod(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;")) {
            return null;
        }

        int classVal = methodInvokeInstr.getUse(0);
        int methodNameVal = methodInvokeInstr.getUse(1);

        if (symbolTable.isStringConstant(methodNameVal)) {
            String methodName = symbolTable.getStringValue(methodNameVal);

            SSAInstruction classDefInstr = defUse.getDef(classVal);
            TypeReference reflectedClassType = null;

            if (classDefInstr instanceof SSAAbstractInvokeInstruction) {
                SSAAbstractInvokeInstruction classInvokeInstr = (SSAAbstractInvokeInstruction)classDefInstr;

                if (classInvokeInstr.getDeclaredTarget().getSignature().equals("java.lang.Object.getClass()Ljava/lang/Class;")) {
                    reflectedClassType = classInvokeInstr.getDeclaredTarget().getDeclaringClass();
                }
            } else if (classDefInstr instanceof SSALoadMetadataInstruction) {
                SSALoadMetadataInstruction classLoadMetaInstr = (SSALoadMetadataInstruction)classDefInstr;

                if (classLoadMetaInstr.getToken() instanceof TypeReference) {
                    //reflectedClassType = TypeReference.findOrCreate(ClassLoaderReference.Extension, ((TypeReference)classLoadMetaInstr.getToken()).getName().toString());
                    reflectedClassType = (TypeReference)classLoadMetaInstr.getToken();
                }
            }

            if (reflectedClassType == null) {
                return null;
            }

            // Need to specify correct class loader
            IClass reflectedClass = _cha.lookupClass(reflectedClassType);
            if (reflectedClass == null) {
                reflectedClass = _cha.lookupClass(TypeReference.findOrCreate(ClassLoaderReference.Extension, reflectedClassType.getName().toString()));
            }
            if (reflectedClass == null) {
                reflectedClass = _cha.lookupClass(TypeReference.findOrCreate(ClassLoaderReference.Application, reflectedClassType.getName().toString()));
            }

            if (reflectedClass == null) {
                return null;
            }

            for (IMethod method : reflectedClass.getDeclaredMethods()) {
                if (method.getSelector().getName().toString().equals(methodName)) {
                    return method;
                }
            }
        }

        return null;
    }

    private IMethod getTargetMethodForDialogFragment(CGNode caller, CallSiteReference site, int fragmentVal) {
        IR ir = caller.getIR();
        DefUse defUse = caller.getDU();

        SSAInstruction defInstr = defUse.getDef(fragmentVal);
        IClass fragmentSubclass = null;

        if (defInstr instanceof SSANewInstruction) {
            SSANewInstruction newInstr = (SSANewInstruction)defInstr;
            IClass newClass = _cha.lookupClass(newInstr.getConcreteType());

            if (newClass != null) {
                if (_cha.isSubclassOf(newClass, _dialogFragmentClass)) {
                    fragmentSubclass = newClass;
                }
            }
        } else if (defInstr instanceof SSAGetInstruction) {
            SSAGetInstruction getInstr = (SSAGetInstruction)defInstr;
            IClass getClass = _cha.lookupClass(getInstr.getDeclaredFieldType());

            if (_cha.isSubclassOf(getClass, _dialogFragmentClass) && !getClass.equals(_dialogFragmentClass)) {
                fragmentSubclass = getClass;
            }
        } else if (defInstr instanceof SSAInvokeInstruction) {
            SSAInvokeInstruction invokeInstr = (SSAInvokeInstruction)defInstr;
            MethodReference invokeMtd = invokeInstr.getDeclaredTarget();
            
            if (invokeMtd.getSelector().getName().toString().equals("newInstance")) {
                IClass invokeClass = _cha.lookupClass(invokeMtd.getDeclaringClass());

                if (_cha.isSubclassOf(invokeClass, _dialogFragmentClass)) {
                    fragmentSubclass = invokeClass;
                }
            }  
        }

        if (fragmentSubclass != null) {
            return fragmentSubclass.getMethod(Selector.make("onCreateDialog(Landroid/os/Bundle;)Landroid/app/Dialog;"));
        }

        return null;
    }
}

