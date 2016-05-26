package intellidroid.appanalysis;

import java.util.*;
import java.io.FileReader;
import java.io.BufferedReader;
import com.ibm.wala.classLoader.*;
import com.ibm.wala.types.*;
import com.ibm.wala.ipa.cha.*;

class AndroidMethods {
    static private MethodReference _onClickMethod;
    static public MethodReference getOnClickMethod() {
        return _onClickMethod;
    }

    static private final Set<MethodReference> _sharedPrefEditorMethods = new HashSet<MethodReference>();
    static public boolean isSharedPrefEditorMethod(MethodReference method) {
        return _sharedPrefEditorMethods.contains(method);
    }

    //static private final List<TypeReference> _prefActivityClasses = new ArrayList<TypeReference>();
    static private final List<MethodReference> _prefXMLMethods = new ArrayList<MethodReference>();
    static public boolean isPreferenceXMLMethod(MethodReference method) {
        return _prefXMLMethods.contains(method);
        //for (TypeReference prefClass : _prefActivityClasses) {
        //    if (cha.isSubclassOf(method.getDeclaringClass(), prefClass)) {
        //        if (method.getSelector().equals(Selector.make("addPreferencesFromResource(I)V"))) {
        //            return true;
        //        }
        //    }
        //}

        //return false;
    }

    //static public enum CallbackRegistrationType {
    //    BROADCAST,
    //    LOCATION
    //}
    //static private final Map<MethodReference, CallbackRegistrationType> _callbackRegMethods = new HashMap<MethodReference, CallbackRegistrationType>();
    //static public boolean isCallbackRegistrationMethod(MethodReference method) {
    //    return _callbackRegMethods.containsKey(method);
    //}
    //static public CallbackRegistrationType getCallbackRegistrationType(MethodReference method) {
    //    return _callbackRegMethods.get(method);
    //}
    static private final Set<TypeReference> _callbackClasses = new HashSet<TypeReference>();
    static public boolean isCallbackClass(TypeReference klass) {
        return _callbackClasses.contains(klass);
    }
    static public Set<TypeReference> getCallbackClasses() {
        return _callbackClasses;
    }

    static private final Set<MethodReference> _uiNotificationMethods = new HashSet<MethodReference>();
    static public boolean isUINotificationMethod(MethodReference method) {
        return _uiNotificationMethods.contains(method);
    }

    static private final TypeReference _intentClass = TypeReference.findOrCreate(ClassLoaderReference.Extension, "Landroid/content/Intent");
    static public boolean isIntentClass(TypeReference type) {
        return _intentClass == type;
    }

    static {
        // OnClick method
        TypeReference onClickListenerClass = TypeReference.findOrCreate(ClassLoaderReference.Extension, "Landroid/view/View$OnClickListener");
        _onClickMethod = MethodReference.findOrCreate(onClickListenerClass, Selector.make("onClick(Landroid/view/View;)V"));

        // SharedPreferences.Editor methods
        TypeReference sharedPrefEditorClass = TypeReference.findOrCreate(ClassLoaderReference.Extension, "Landroid/content/SharedPreferences$Editor");
        _sharedPrefEditorMethods.add(MethodReference.findOrCreate(sharedPrefEditorClass, Selector.make("putString(Ljava/lang/String;Ljava/lang/String;)Landroid/content/SharedPreferences$Editor;")));
        _sharedPrefEditorMethods.add(MethodReference.findOrCreate(sharedPrefEditorClass, Selector.make("putStringSet(Ljava/lang/String;Ljava/util/Set;)Landroid/content/SharedPreferences$Editor;")));
        _sharedPrefEditorMethods.add(MethodReference.findOrCreate(sharedPrefEditorClass, Selector.make("putInt(Ljava/lang/String;I)Landroid/content/SharedPreferences$Editor;")));
        _sharedPrefEditorMethods.add(MethodReference.findOrCreate(sharedPrefEditorClass, Selector.make("putLong(Ljava/lang/String;J)Landroid/content/SharedPreferences$Editor;")));
        _sharedPrefEditorMethods.add(MethodReference.findOrCreate(sharedPrefEditorClass, Selector.make("putFloat(Ljava/lang/String;F)Landroid/content/SharedPreferences$Editor;")));
        _sharedPrefEditorMethods.add(MethodReference.findOrCreate(sharedPrefEditorClass, Selector.make("putBoolean(Ljava/lang/String;Z)Landroid/content/SharedPreferences$Editor;")));

        // Preferences XML methods
        TypeReference prefActivityClass = TypeReference.findOrCreate(ClassLoaderReference.Extension, "Landroid/preference/PreferenceActivity");
        //_prefActivityClasses.add(prefActivityClass);
        _prefXMLMethods.add(MethodReference.findOrCreate(prefActivityClass, Selector.make("addPreferencesFromResource(I)V")));

        TypeReference prefFragmentClass = TypeReference.findOrCreate(ClassLoaderReference.Extension, "Landroid/preference/PreferenceFragment");
        //_prefActivityClasses.add(prefFragmentClass);
        _prefXMLMethods.add(MethodReference.findOrCreate(prefFragmentClass, Selector.make("addPreferencesFromResource(I)V")));

        // Callback registration methods
        //TypeReference contextClass = TypeReference.findOrCreate(ClassLoaderReference.Extension, "Landroid/content/Context");
        //TypeReference contextWrapperClass = TypeReference.findOrCreate(ClassLoaderReference.Extension, "Landroid/content/ContextWrapper");
        //_callbackRegMethods.put(MethodReference.findOrCreate(contextClass, Selector.make("registerReceiver(Landroid/content/BroadcastReceiver;Landroid/content/IntentFilter;)Landroid/content/Intent;")), CallbackRegistrationType.BROADCAST);
        //_callbackRegMethods.put(MethodReference.findOrCreate(contextClass, Selector.make("registerReceiver(Landroid/content/BroadcastReceiver;Landroid/content/IntentFilter;Ljava/lang/String;Landroid/os/Handler;)Landroid/content/Intent;")), CallbackRegistrationType.BROADCAST);
        //_callbackRegMethods.put(MethodReference.findOrCreate(contextWrapperClass, Selector.make("registerReceiver(Landroid/content/BroadcastReceiver;Landroid/content/IntentFilter;)Landroid/content/Intent;")), CallbackRegistrationType.BROADCAST);
        //_callbackRegMethods.put(MethodReference.findOrCreate(contextWrapperClass, Selector.make("registerReceiver(Landroid/content/BroadcastReceiver;Landroid/content/IntentFilter;Ljava/lang/String;Landroid/os/Handler;)Landroid/content/Intent;")), CallbackRegistrationType.BROADCAST);

        try {
            BufferedReader br = new BufferedReader(new FileReader("./android/AndroidCallbacks.txt"));
            String line;
            while ((line = br.readLine()) != null) {
                TypeReference callbackClass = TypeReference.findOrCreate(ClassLoaderReference.Extension, line);
                _callbackClasses.add(callbackClass);
            }

            br.close();

        } catch (Exception e) {
            System.err.println("Exception: " + e.toString());
            e.printStackTrace();
        }

        // UI notification methods
        TypeReference dialogFragmentClass = TypeReference.findOrCreate(ClassLoaderReference.Extension, "Landroid/app/DialogFragment");
        _uiNotificationMethods.add(MethodReference.findOrCreate(dialogFragmentClass, Selector.make("show(Landroid/app/FragmentManager;Ljava/lang/String;)V")));
        _uiNotificationMethods.add(MethodReference.findOrCreate(dialogFragmentClass, Selector.make("show(Landroid/app/FragmentTransaction;Ljava/lang/String;)I")));

        TypeReference toastClass = TypeReference.findOrCreate(ClassLoaderReference.Extension, "Landroid/widget/Toast");
        _uiNotificationMethods.add(MethodReference.findOrCreate(toastClass, Selector.make("show()V")));

        TypeReference textViewClass = TypeReference.findOrCreate(ClassLoaderReference.Extension, "Landroid/widget/TextView");
        _uiNotificationMethods.add(MethodReference.findOrCreate(textViewClass, Selector.make("setText(Ljava/lang/CharSequence;)V")));
        _uiNotificationMethods.add(MethodReference.findOrCreate(textViewClass, Selector.make("setText(Ljava/lang/CharSequence;Landroid/widget/TextView$BufferType;)V")));
        _uiNotificationMethods.add(MethodReference.findOrCreate(textViewClass, Selector.make("setText([CII)V")));
        _uiNotificationMethods.add(MethodReference.findOrCreate(textViewClass, Selector.make("setText(I)V")));
        _uiNotificationMethods.add(MethodReference.findOrCreate(textViewClass, Selector.make("setText(ILandroid/widget/TextView$BufferType;)V")));

        TypeReference editTextClass = TypeReference.findOrCreate(ClassLoaderReference.Extension, "Landroid/widget/EditText");
        _uiNotificationMethods.add(MethodReference.findOrCreate(editTextClass, Selector.make("setText(Ljava/lang/CharSequence;Landroid/widget/TextView$BufferType;)V")));

        TypeReference textSwitcherClass = TypeReference.findOrCreate(ClassLoaderReference.Extension, "Landroid/widget/TextSwitcher");
        _uiNotificationMethods.add(MethodReference.findOrCreate(textSwitcherClass, Selector.make("setText(Ljava/lang/CharSequence;)V")));
        _uiNotificationMethods.add(MethodReference.findOrCreate(textSwitcherClass, Selector.make("setCurrentText(Ljava/lang/CharSequence;)V")));

        TypeReference autoCompleteTextViewClass = TypeReference.findOrCreate(ClassLoaderReference.Extension, "Landroid/widget/TextSwitcher");
        _uiNotificationMethods.add(MethodReference.findOrCreate(autoCompleteTextViewClass, Selector.make("setText(Ljava/lang/CharSequence;Z)V")));
    }


}

