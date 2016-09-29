package intellidroid.appanalysis;

import java.util.*;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.DocumentBuilder;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import org.w3c.dom.Node;
import org.w3c.dom.Element;
import java.io.File;

class ManifestAnalysis {
    private static final boolean DEBUG = false;

    private final String _manifestPath;
    private String _packageName = "";
    private String _applicationName = "";
    private String _mainActivityName = "";
    private String _mainServiceName = "";
    private Map<String, String> _actionServiceMap = new HashMap<String, String>();
    private Map<String, List<String>> _receiverActionMap = new HashMap<String, List<String>>();

    private List<String> _activities = new ArrayList<String>();
    private List<String> _services = new ArrayList<String>();
    private List<String> _broadcastReceivers = new ArrayList<String>();
    private List<String> _contentProviders = new ArrayList<String>();

    public ManifestAnalysis(String manifestPath) {
        _manifestPath = manifestPath;

        processManifest();
    }

    public String getMainActivityName() {
        return _mainActivityName;
    }

    public String getMainServiceName() {
        return _mainServiceName;
    }

    public String getPackageName() {
        return _packageName;
    }

    public String getApplicationName() {
        return _applicationName;
    }

    public String getServiceForActionString(String actionString) {
        if (_actionServiceMap.containsKey(actionString)) {
            return _actionServiceMap.get(actionString);
        }

        return null;
    }

    public List<String> getActionsForReceiver(String receiver) {
        if (_receiverActionMap.containsKey(receiver)) {
            return _receiverActionMap.get(receiver);
        }

        return null;
    }

    public List<String> getActivities() {
        return _activities;
    }

    public List<String> getServices() {
        return _services;
    }

    public List<String> getBroadcastReceivers() {
        return _broadcastReceivers;
    }

    public List<String> getContentProviders() {
        return _contentProviders;
    }

    private void processManifest() {
        try {
            File manifestFile = new File(_manifestPath);

            DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
            DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
            Document manifestXML = dBuilder.parse(manifestFile);
            manifestXML.getDocumentElement().normalize();

            Element manifestElement = (Element)manifestXML.getElementsByTagName("manifest").item(0);
            _packageName = manifestElement.getAttribute("package");

            Element applicationElement = (Element)manifestXML.getElementsByTagName("application").item(0);
            _applicationName = getFullClassName(applicationElement.getAttribute("android:name"));

            processActivities(manifestXML);
            processServices(manifestXML);
            processReceivers(manifestXML);
            processProviders(manifestXML);

        } catch (Exception e) {
            System.err.println("Exception: " + e.toString());
            e.printStackTrace();
        }

    }

    private void processActivities(Document manifestXML) {
        NodeList activityNodes = manifestXML.getElementsByTagName("activity");

        for (int i = 0; i < activityNodes.getLength(); i++) {
            Node activityNode = activityNodes.item(i);

            if (activityNode.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }

            Element activityElement = (Element)activityNode;
            String activityName = getFullClassName(activityElement.getAttribute("android:name"));
            _activities.add(activityName);

            //NodeList intentNodes = activityElement.getElementsByTagName("intent-filter");
            //if (intentNodes.getLength() == 0) {
            //    continue;
            //}
            //Node intentNode = intentNodes.item(0);

            NodeList actionNodes = activityElement.getElementsByTagName("action");

            for (int a = 0; a < actionNodes.getLength(); a++) {
                Element actionElement = (Element)actionNodes.item(a);
                String actionString = actionElement.getAttribute("android:name");

                if (actionString.equals("android.intent.action.MAIN")) {
                    _mainActivityName = activityName;
                    Output.debug(DEBUG, "Manifest main activity: " + _mainActivityName);
                }
            }
        }
    }

    private void processServices(Document manifestXML) {
        NodeList serviceNodes = manifestXML.getElementsByTagName("service");

        for (int i = 0; i < serviceNodes.getLength(); i++) {
            Node serviceNode = serviceNodes.item(i);

            if (serviceNode.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }

            Element serviceElement = (Element)serviceNode;
            String serviceName = getFullClassName(serviceElement.getAttribute("android:name"));
            _services.add(serviceName);

            //NodeList intentNodes = serviceElement.getElementsByTagName("intent-filter");
            //if (intentNodes.getLength() == 0) {
            //    continue;
            //}
            //Node intentNode = intentNodes.item(0);

            NodeList actionNodes = serviceElement.getElementsByTagName("action");

            for (int a = 0; a < actionNodes.getLength(); a++) {
                Element actionElement = (Element)actionNodes.item(a);
                String actionString = actionElement.getAttribute("android:name");

                _actionServiceMap.put(actionString, serviceName);
            }
        }

        if (DEBUG) {
            Output.debug(DEBUG, "===============================================");
            Output.debug(DEBUG, "Manifest Analysis: Services");
            for (String actionString : _actionServiceMap.keySet()) {
                Output.debug(DEBUG, actionString + ": " + _actionServiceMap.get(actionString));
            }
            Output.debug(DEBUG, "===============================================");
        }
    }

    private void processReceivers(Document manifestXML) {
        NodeList receiverNodes = manifestXML.getElementsByTagName("receiver");

        for (int i = 0; i < receiverNodes.getLength(); i++) {
            Node receiverNode = receiverNodes.item(i);

            if (receiverNode.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }

            Element receiverElement = (Element)receiverNode;
            String receiverName = getFullClassName(receiverElement.getAttribute("android:name"));
            _broadcastReceivers.add(receiverName);

            //NodeList intentNodes = receiverElement.getElementsByTagName("intent-filter");
            //if (intentNodes.getLength() == 0) {
            //    continue;
            //}
            //Node intentNode = intentNodes.item(0);

            // actions should be within intent-filter
            NodeList actionNodes = receiverElement.getElementsByTagName("action");

            for (int a = 0; a < actionNodes.getLength(); a++) {
                Element actionElement = (Element)actionNodes.item(a);
                String actionString = actionElement.getAttribute("android:name");

                if (!_receiverActionMap.containsKey(receiverName)) {
                    _receiverActionMap.put(receiverName, new ArrayList<String>());
                }

                _receiverActionMap.get(receiverName).add(actionString);
            }
        }

        if (DEBUG) {
            Output.debug(DEBUG, "Manifest Analysis: Receivers");
            for (String receiverName : _receiverActionMap.keySet()) {
                Output.debug(DEBUG, receiverName + ": ");
                for (String actionString : _receiverActionMap.get(receiverName)) {
                    Output.debug(DEBUG, "    " + actionString);
                }
            }
            Output.debug(DEBUG, "===============================================");
        }
    }

    private void processProviders(Document manifestXML) {
        NodeList providerNodes = manifestXML.getElementsByTagName("provider");

        for (int i = 0; i < providerNodes.getLength(); i++) {
            Node providerNode = providerNodes.item(i);

            if (providerNode.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }

            Element providerElement = (Element)providerNode;
            String providerName = getFullClassName(providerElement.getAttribute("android:name"));
            _contentProviders.add(providerName);

            //NodeList actionNodes = providerElement.getElementsByTagName("action");

            //for (int a = 0; a < actionNodes.getLength(); a++) {
            //    Element actionElement = (Element)actionNodes.item(a);
            //    String actionString = actionElement.getAttribute("android:name");

            //    if (!_providerActionMap.containsKey(providerName)) {
            //        _providerActionMap.put(providerName, new ArrayList<String>());
            //    }

            //    _providerActionMap.get(providerName).add(actionString);
            //}
        }

        //Output.debug(DEBUG, "===============================================");
        //Output.debug(DEBUG, "Manifest Analysis: Providers");
        //for (String providerName : _providerActionMap.keySet()) {
        //    Output.debug(DEBUG, providerName + ": ");

        //    for (String actionString : _providerActionMap.get(providerName)) {
        //        Output.debug(DEBUG, "    " + actionString);
        //    }
        //}
        //Output.debug(DEBUG, "===============================================");
    }

    private String getFullClassName(String componentName) {
        if (componentName.startsWith(".")) {
            return _packageName + componentName;
        } else if (!componentName.contains(".")) {
            return _packageName + "." +  componentName;
        } else {
            return componentName;
        }
    }
}

