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

import java.util.*;

class AndroidAppClassTargetSelector implements ClassTargetSelector {
    private final boolean DEBUG = false;

    ClassTargetSelector _childSelector;
    IClassHierarchy _cha;

    public AndroidAppClassTargetSelector(ClassTargetSelector childSelector, IClassHierarchy cha) {
        _childSelector = childSelector;
        _cha = cha;
    }

    @Override
    public IClass getAllocatedTarget(CGNode caller, NewSiteReference site) {
        Output.debug(DEBUG, "getAllocatedTarget: " + caller.getMethod().getSignature());
        Output.debug(DEBUG, "    site: " + site.toString());

        TypeReference declaredType = site.getDeclaredType();
        IClass declaredClass = _cha.lookupClass(declaredType);

        Output.debug(DEBUG, "    child selector: " + _childSelector.getAllocatedTarget(caller,site));
        return _childSelector.getAllocatedTarget(caller, site);
    }
}

