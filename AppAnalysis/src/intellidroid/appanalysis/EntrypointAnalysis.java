package intellidroid.appanalysis;

import com.ibm.wala.classLoader.*;
import com.ibm.wala.types.*;
import com.ibm.wala.ssa.*;

import com.ibm.wala.ipa.callgraph.*;
import com.ibm.wala.ipa.callgraph.impl.*;
import com.ibm.wala.ipa.cha.IClassHierarchy;
import com.ibm.wala.ipa.callgraph.propagation.PointerAnalysis;
import com.ibm.wala.ipa.callgraph.propagation.SSAContextInterpreter;
import com.ibm.wala.ipa.callgraph.propagation.SSAPropagationCallGraphBuilder;
import com.ibm.wala.ipa.callgraph.propagation.cfa.ZeroXCFABuilder;
import com.ibm.wala.ipa.callgraph.propagation.cfa.ZeroXInstanceKeys;

import java.util.*;
import java.io.FileReader;
import java.io.BufferedReader;

// TODO: Actually make call graph generation incremental

class EntrypointAnalysis {
    private static final boolean DEBUG = false;

    private final IClassHierarchy _cha;
    private final ManifestAnalysis _manifestAnalysis;
    private final UIActivityMapping _uiActivityMapping;
    private CallGraphInfoListener _callGraphInfoListener;

    private Map<IMethod, MethodReference> _entrypointFrameworkMethodMap = new LinkedHashMap<IMethod, MethodReference>();
    private List<IMethod> _trueEntrypoints = new ArrayList<IMethod>();

    private SSAPropagationCallGraphBuilder _callGraphBuilder = null;
    private CallGraph _callGraph = null;
    private PointerAnalysis _pointerAnalysis = null;

    private static final TypeReference _applicationClass;
    private static final TypeReference _activityClass;
    private static final TypeReference _serviceClass;
    private static final TypeReference _receiverClass;
    private static final TypeReference _providerClass;
    private static final TypeReference _fragmentClass;

    private static final Set<Selector> _applicationLifecycleMethods;
    private static final Set<Selector> _activityLifecycleMethods;
    private static final Set<Selector> _serviceLifecycleMethods;
    private static final Selector _receiverLifecycleMethod;
    private static final Selector _providerLifecycleMethod;
    private static final Set<Selector> _fragmentLifecycleMethods; 

    private Set<TypeReference> _activities = new HashSet<TypeReference>();
    private Set<TypeReference> _fragments = new HashSet<TypeReference>();

    static {
        _applicationClass = TypeReference.findOrCreate(ClassLoaderReference.Extension, "Landroid/app/Application");
        _activityClass = TypeReference.findOrCreate(ClassLoaderReference.Extension, "Landroid/app/Activity");
        _serviceClass = TypeReference.findOrCreate(ClassLoaderReference.Extension, "Landroid/app/Service");
        _receiverClass = TypeReference.findOrCreate(ClassLoaderReference.Extension, "Landroid/content/BroadcastReceiver");
        _providerClass = TypeReference.findOrCreate(ClassLoaderReference.Extension, "Landroid/content/ContentProvider");
        _fragmentClass = TypeReference.findOrCreate(ClassLoaderReference.Extension, "Landroid/app/Fragment");

        _applicationLifecycleMethods = new HashSet<Selector>();
        _applicationLifecycleMethods.add(Selector.make("onCreate()V"));
        _applicationLifecycleMethods.add(Selector.make("onTerminate()V"));
        _applicationLifecycleMethods.add(Selector.make("onConfigurationChanged(Landroid/content/res/Configuration;)V"));
        _applicationLifecycleMethods.add(Selector.make("onLowMemory()V"));
        _applicationLifecycleMethods.add(Selector.make("onTrimMemory()V"));
        _applicationLifecycleMethods.add(Selector.make("attachBaseContext(Landroid/content/Context;)V"));

        _activityLifecycleMethods = new HashSet<Selector>();
        _activityLifecycleMethods.add(Selector.make("onCreate(Landroid/os/Bundle;)V"));
        _activityLifecycleMethods.add(Selector.make("onStart()V"));
        _activityLifecycleMethods.add(Selector.make("onResume()V"));
        _activityLifecycleMethods.add(Selector.make("onPause()V"));
        _activityLifecycleMethods.add(Selector.make("onStop()V"));
        _activityLifecycleMethods.add(Selector.make("onRestart()V"));
        _activityLifecycleMethods.add(Selector.make("onDestroy()V"));

        _serviceLifecycleMethods = new HashSet<Selector>();
        _serviceLifecycleMethods.add(Selector.make("onCreate()V"));
        _serviceLifecycleMethods.add(Selector.make("onStart(Landroid/content/Intent;I)V"));
        _serviceLifecycleMethods.add(Selector.make("onStartCommand(Landroid/content/Intent;II)I"));
        _serviceLifecycleMethods.add(Selector.make("onDestroy()V"));
        //_serviceLifecycleMethods.add(Selector.make("onBind(Landroid/content/Intent;)Landroid/os/IBinder;"));
        //_serviceLifecycleMethods.add(Selector.make("onUnbind(Landroid/content/Intent;)Z"));
        //_serviceLifecycleMethods.add(Selector.make("onRebind(Landroid/content/Intent;)V"));

        _receiverLifecycleMethod = Selector.make("onReceive(Landroid/content/Context;Landroid/content/Intent;)V");
        _providerLifecycleMethod = Selector.make("onCreate()Z");

        _fragmentLifecycleMethods = new HashSet<Selector>();
        _fragmentLifecycleMethods.add(Selector.make("onAttach(Landroid/app/Activity;)V"));
        _fragmentLifecycleMethods.add(Selector.make("onCreate(Landroid/os/Bundle;)V"));
        _fragmentLifecycleMethods.add(Selector.make("onCreateView(Landroid/view/LayoutInflater;Landroid/view/ViewGroup;Landroid/os/Bundle;)Landroid/view/View;"));
        _fragmentLifecycleMethods.add(Selector.make("onActivityCreated(Landroid/os/Bundle;)V"));
        _fragmentLifecycleMethods.add(Selector.make("onViewStateRestored(Landroid/os/Bundle;)V"));
        _fragmentLifecycleMethods.add(Selector.make("onStart()V"));
        _fragmentLifecycleMethods.add(Selector.make("onResume()V"));
        _fragmentLifecycleMethods.add(Selector.make("onPause()V"));
        _fragmentLifecycleMethods.add(Selector.make("onStop()V"));
        _fragmentLifecycleMethods.add(Selector.make("onDestroyView()V"));
        _fragmentLifecycleMethods.add(Selector.make("onDestroy()V"));
        _fragmentLifecycleMethods.add(Selector.make("onDetach()V"));
    }

    public EntrypointAnalysis(IClassHierarchy cha, ManifestAnalysis manifestAnalysis, UIActivityMapping uiActivityMapping, CallGraphInfoListener callGraphInfoListener) {
        _cha = cha;
        _manifestAnalysis = manifestAnalysis;
        _uiActivityMapping = uiActivityMapping;
        _callGraphInfoListener = callGraphInfoListener;

        // Get entrypoints for components listed in manifest
        Map<IMethod, MethodReference> componentEntrypoints = getComponentEntrypoints();
        List<Entrypoint> incrementalEntrypoints = new ArrayList<Entrypoint>();

        for (IMethod entrypoint : componentEntrypoints.keySet()) {
            _entrypointFrameworkMethodMap.put(entrypoint, componentEntrypoints.get(entrypoint));
            incrementalEntrypoints.add(new AndroidEntrypoint(entrypoint, _cha));
        }

        // For all callback listener types, find subclasses/implementors and look for
        // their constructors in the call graph.  If found, add overriden methods to 
        // entrypoints.
        // Do this instead of looking for callback registrations, since the class type
        // may not be immediately obvious and it can be costly to get this information
        // precisely.

        Output.debug(DEBUG, "Callback entrypoints:");
        boolean changed = true;

        while(changed) {
            changed = false;
            makeCallgraphIncremental(incrementalEntrypoints, _cha.getScope(), _cha);
            //incrementalEntrypoints.clear();

            for (TypeReference callbackClassType : AndroidMethods.getCallbackClasses()) {
                IClass callbackClass = _cha.lookupClass(callbackClassType);
                if (callbackClass == null) {
                    //Output.error("Null class: " + callbackClassType.toString());
                    continue;
                }

                boolean classInstantiated = false;

                for (IClass callbackSubclass : callbackClass.isInterface() ? _cha.getImplementors(callbackClassType) : _cha.computeSubClasses(callbackClassType)) {
                    if (!callbackSubclass.getClassLoader().getReference().equals(ClassLoaderReference.Application)) {
                        continue;
                    }

                    for (IMethod classMethod : callbackSubclass.getDeclaredMethods()) {
                        if (classMethod.isInit()) {
                            Set<CGNode> initNodes = _callGraph.getNodes(classMethod.getReference());
                            if (initNodes != null && initNodes.size() > 0) {
                                classInstantiated = true;
                                break;
                            }
                        }
                    }

                    // Handle the case where the onClick method is declared inside the UI layout file
                    //if (!classInstantiated &&
                    //    callbackSubclass.getSuperclass() != null &&
                    //    callbackSubclass.getSuperclass().getReference().equals(_activityClass)) {

                    //    classInstantiated = true;
                    //}

                    if (!classInstantiated) {
                        continue;
                    }

                    // Callback subclass has been instantiated in call graph.  Add overridden methods
                    // as entrypoints.
                    for (IMethod frameworkMethod : callbackClass.getDeclaredMethods()) {
                        if (frameworkMethod.isInit()) {
                            continue;
                        }

                        IMethod subclassMethod = callbackSubclass.getMethod(frameworkMethod.getSelector());
                        if (subclassMethod == null) {
                            continue;
                        }
                        
                        if (subclassMethod.getDeclaringClass().equals(callbackSubclass)) {
                            if (!_entrypointFrameworkMethodMap.containsKey(subclassMethod)) {
                                _entrypointFrameworkMethodMap.put(subclassMethod, frameworkMethod.getReference());
                                incrementalEntrypoints.add(new AndroidEntrypoint(subclassMethod, _cha));
                                changed = true;

                                Output.debug(DEBUG, "    " + subclassMethod.getSignature());
                            }
                        }
                    }
                }
            }
        }

        // Get the true entrypoints (i.e. entrypoints that aren't part of another entrypoint's path
        for (IMethod entrypoint : _entrypointFrameworkMethodMap.keySet()) {
            Set<CGNode> entrypointNodes = _callGraph.getNodes(entrypoint.getReference());
            if (entrypointNodes.isEmpty()) {
                continue;
            }

            CGNode entrypointNode = entrypointNodes.iterator().next();
            boolean embedded = false;
            Iterator<CGNode> predNodesIter = _callGraph.getPredNodes(entrypointNode);

            while (predNodesIter.hasNext()) {
                CGNode predNode = predNodesIter.next();
                if (!predNode.equals(_callGraph.getFakeRootNode())) {
                    embedded = true;
                }
            }

            if (embedded) {
                Output.debug(DEBUG, "Embedded entrypoint: " + entrypoint.getSignature());
            } else {
                _trueEntrypoints.add(entrypoint);
            }
        }

        if (DEBUG) {
            Output.debug(DEBUG, "===================================");
            for (IMethod entrypoint : _entrypointFrameworkMethodMap.keySet()) {
                Output.debug(DEBUG, "Map: " + entrypoint.toString() + " --> " + _entrypointFrameworkMethodMap.get(entrypoint).getSignature());
            }
            Output.debug(DEBUG, "===================================");
        }
    }

    public Collection<IMethod> getEntrypoints() {
        //return _entrypointFrameworkMethodMap.keySet();
        return _trueEntrypoints;
    }

    public MethodReference getOverriddenFrameworkMethod(IMethod entrypoint) {
        if (_entrypointFrameworkMethodMap.containsKey(entrypoint)) {
            return _entrypointFrameworkMethodMap.get(entrypoint);
        }

        return null;
    }

    public Set<TypeReference> getAllActivities() {
        return _activities;
    }

    public CallGraph getCallGraph() {
        return _callGraph;
    }

    public PointerAnalysis getPointerAnalysis() {
        return _pointerAnalysis;
    }

    private Map<IMethod, MethodReference> getComponentEntrypoints() {
        Map<IMethod, MethodReference> componentEntrypoints = new HashMap<IMethod, MethodReference>();

        componentEntrypoints.putAll(getApplicationEntrypoints());
        componentEntrypoints.putAll(getActivityEntrypoints());
        componentEntrypoints.putAll(getServiceEntrypoints());
        componentEntrypoints.putAll(getBroadcastReceiverEntrypoints());
        componentEntrypoints.putAll(getContentProviderEntrypoints());
        componentEntrypoints.putAll(getFragmentEntrypoints());

        if (DEBUG) {
            Output.debug(DEBUG, "Component entrypoints:");
            for (IMethod entrypoint : componentEntrypoints.keySet()) {
                Output.debug(DEBUG, "    " + entrypoint.getSignature());
            }
            Output.debug(DEBUG, "===================================");
        }

        return componentEntrypoints;
    }

    private Map<IMethod, MethodReference> getApplicationEntrypoints() {
        Map<IMethod, MethodReference> applicationEntrypoints = new HashMap<IMethod, MethodReference>();
        String applicationName = convertClassNameToWALA(_manifestAnalysis.getApplicationName());

        if (applicationName.isEmpty()) {
            return applicationEntrypoints;
        }

        IClass applicationClass = _cha.lookupClass(TypeReference.findOrCreate(ClassLoaderReference.Application, applicationName));
        if (applicationClass == null) {
            return applicationEntrypoints;
        }

        for (IMethod applicationMethod : applicationClass.getDeclaredMethods()) {
            if (_applicationLifecycleMethods.contains(applicationMethod.getSelector())) {
                applicationEntrypoints.put(applicationMethod, MethodReference.findOrCreate(_applicationClass, applicationMethod.getSelector()));
            }
        }

        return applicationEntrypoints;
    }

    private Map<IMethod, MethodReference> getActivityEntrypoints() {
        Map<IMethod, MethodReference> activityEntrypoints = new HashMap<IMethod, MethodReference>();
        List<String> activities = _manifestAnalysis.getActivities();
        Set<String> uiDefinedHandlers = _uiActivityMapping.getUILayoutDefinedHandlers();

        for (String activityName : activities) {
            String className = convertClassNameToWALA(activityName);
            IClass activityClass = _cha.lookupClass(TypeReference.findOrCreate(ClassLoaderReference.Application, className));

            if (activityClass == null) {
                Output.debug(DEBUG, "Missing manifest component: " + className);
                continue;
            }

            _activities.add(activityClass.getReference());

            for (IMethod activityMethod : activityClass.getDeclaredMethods()) {
                if (_activityLifecycleMethods.contains(activityMethod.getSelector())) {
                    activityEntrypoints.put(activityMethod, MethodReference.findOrCreate(_activityClass, activityMethod.getSelector()));
                } else if (uiDefinedHandlers.contains(activityMethod.getSelector().getName().toString())) {
                    activityEntrypoints.put(activityMethod, AndroidMethods.getOnClickMethod());
                }
            }
        }

        return activityEntrypoints;
    }
    
    private Map<IMethod, MethodReference> getFragmentEntrypoints() {
        Map<IMethod, MethodReference> fragmentEntrypoints = new HashMap<IMethod, MethodReference>();
      
        Collection<IClass> fragments = _cha.computeSubClasses(TypeReference.findOrCreate(ClassLoaderReference.Extension, "Landroid/app/Fragment")); 

        for (IClass fragmentClass : fragments) {
            _fragments.add(fragmentClass.getReference());

            for (IMethod fragmentMethod : fragmentClass.getDeclaredMethods()) {
                if (_fragmentLifecycleMethods.contains(fragmentMethod.getSelector())) {
                    fragmentEntrypoints.put(fragmentMethod, MethodReference.findOrCreate(_fragmentClass, fragmentMethod.getSelector()));
                }
            }
        }

        return fragmentEntrypoints;
    }

    private Map<IMethod, MethodReference> getServiceEntrypoints() {
        Map<IMethod, MethodReference> serviceEntrypoints = new HashMap<IMethod, MethodReference>();
        List<String> services = _manifestAnalysis.getServices();

        for (String serviceName : services) {
            String className = convertClassNameToWALA(serviceName);
            IClass serviceClass = _cha.lookupClass(TypeReference.findOrCreate(ClassLoaderReference.Application, className));

            if (serviceClass == null) {
                Output.debug(DEBUG, "Missing manifest component: " + className);
                continue;
            }

            for (IMethod serviceMethod : serviceClass.getDeclaredMethods()) {
                if (_serviceLifecycleMethods.contains(serviceMethod.getSelector())) {
                    serviceEntrypoints.put(serviceMethod, MethodReference.findOrCreate(_serviceClass, serviceMethod.getSelector()));
                }
            }
        }

        return serviceEntrypoints;
    }

    private Map<IMethod, MethodReference> getBroadcastReceiverEntrypoints() {
        Map<IMethod, MethodReference> receiverEntrypoints = new HashMap<IMethod, MethodReference>();
        List<String> receivers = _manifestAnalysis.getBroadcastReceivers();

        for (String receiverName : receivers) {
            String className = convertClassNameToWALA(receiverName);
            IClass receiverClass = _cha.lookupClass(TypeReference.findOrCreate(ClassLoaderReference.Application, className));

            if (receiverClass == null) {
                Output.debug(DEBUG, "Missing manifest component: " + className);
                continue;
            }

            IMethod onReceiveMethod = receiverClass.getMethod(_receiverLifecycleMethod);

            if (onReceiveMethod != null) {
                receiverEntrypoints.put(onReceiveMethod, MethodReference.findOrCreate(_receiverClass, _receiverLifecycleMethod));
            }
        }

        return receiverEntrypoints;
    }

    private Map<IMethod, MethodReference> getContentProviderEntrypoints() {
        Map<IMethod, MethodReference> providerEntrypoints = new HashMap<IMethod, MethodReference>();
        List<String> providers = _manifestAnalysis.getContentProviders();

        for (String providerName : providers) {
            String className = convertClassNameToWALA(providerName);
            IClass providerClass = _cha.lookupClass(TypeReference.findOrCreate(ClassLoaderReference.Application, className));

            if (providerClass == null) {
                Output.debug(DEBUG, "Missing manifest component: " + className);
                continue;
            }

            IMethod onCreateMethod = providerClass.getMethod(_providerLifecycleMethod);

            if (onCreateMethod != null) {
                providerEntrypoints.put(onCreateMethod, MethodReference.findOrCreate(_providerClass, _providerLifecycleMethod));
            }
        }

        return providerEntrypoints;
    }

    private String convertClassNameToWALA(String className) {
        String walaName = "L" + className.replace('.', '/');
        return walaName;
    }

    private List<IClass> getCallbackClasses() {
        List<IClass> callbackClasses = new ArrayList<IClass>();
        Set<TypeReference> frameworkCallbacks = AndroidMethods.getCallbackClasses();

        for (TypeReference frameworkClass : frameworkCallbacks) {
            //TypeReference callbackClass = TypeReference.findOrCreate(ClassLoaderReference.Extension, line);
            Collection<IClass> subclasses = _cha.computeSubClasses(frameworkClass);

            for (IClass subclass : subclasses) {
                if (!subclass.getClassLoader().getReference().equals(ClassLoaderReference.Application)) {
                    continue;
                }

                callbackClasses.add(subclass);
            }
        }

        return callbackClasses;
    }

    private void makeCallgraphIncremental(Iterable<Entrypoint> entrypoints, AnalysisScope scope, IClassHierarchy cha) {
        try {
            //if (_callGraphBuilder == null) {
                AnalysisOptions options = new AnalysisOptions(scope, entrypoints);
                options.setSelector(new AndroidAppMethodTargetSelector(new ClassHierarchyMethodTargetSelector(cha), cha, _manifestAnalysis));
                options.setSelector(new ClassHierarchyClassTargetSelector(cha));
                //options.setReflectionOptions(AnalysisOptions.ReflectionOptions.FULL);

                _callGraphBuilder = ZeroXCFABuilder.make(cha, options, new AnalysisCache(), new DefaultContextSelector(options, cha), null, ZeroXInstanceKeys.NONE);
                _callGraphInfoListener.clear();
                _callGraphBuilder.setBuilderListener(_callGraphInfoListener);
            //}

            //AnalysisOptions options = _callGraphBuilder.getOptions();
            //options.setEntrypoints(entrypoints);
            _callGraph = _callGraphBuilder.makeCallGraph(options, null);
            _pointerAnalysis = _callGraphBuilder.getPointerAnalysis();

            Output.debug(DEBUG, "< Call graph created >");

        } catch (Exception e) {
            Output.error("Error: " + e.toString());
            e.printStackTrace();
        }
    }
}
