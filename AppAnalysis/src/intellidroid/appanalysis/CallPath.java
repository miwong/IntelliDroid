package intellidroid.appanalysis;

import com.ibm.wala.classLoader.*;
import com.ibm.wala.types.*;
import com.ibm.wala.ipa.callgraph.*;
import com.ibm.wala.ipa.callgraph.propagation.*;

import java.util.*;
import org.apache.commons.lang3.builder.EqualsBuilder;
import org.apache.commons.lang3.builder.HashCodeBuilder;

class CallPath {
    private final List<CGNode> _callPath;
    private final Set<CGNode> _callNodes;
    //private final CallSiteReference _callbackSite;
    private final ProgramCounter _target;
    private final int _targetIndex;
    private final CallGraph _callgraph;
    private final PointerAnalysis _pointerAnalysis;

    public CallPath(List<CGNode> callPath, ProgramCounter target, int targetIndex, CallGraph cg, PointerAnalysis pa) {
        _callPath = callPath;
        //_callbackSite = callbackSite;
        _target = target;
        _targetIndex = targetIndex;
        _callgraph = cg;
        _pointerAnalysis = pa;

        _callNodes = new HashSet<CGNode>();
        _callNodes.addAll(callPath);
    }

    public CallPath(List<CGNode> callPath, int targetIndex, CallGraph cg, PointerAnalysis pa) {
        _callPath = callPath;
        //_callbackSite = callbackSite;
        _targetIndex = targetIndex;
        _target = null;
        _callgraph = cg;
        _pointerAnalysis = pa;

        _callNodes = new HashSet<CGNode>();
        _callNodes.addAll(callPath);
    }

    public List<CGNode> getPath() {
        return _callPath;
    }

    public CallSiteReference getTargetCallSite() {
        if (_target instanceof CallSiteReference) {
            return (CallSiteReference)_target;
        }

        return null;
    }

    public ProgramCounter getTarget() {
        return _target;
    }

    public int getTargetIndex() {
        return _targetIndex;
    }

    public CallGraph getCallGraph() {
        return _callgraph;
    }

    public PointerAnalysis getPointerAnalysis() {
        return _pointerAnalysis;
    }

    public boolean containsNode(CGNode node) {
        return _callNodes.contains(node);
    }

    public boolean equals(Object obj) {
        if (obj == null) {
            return false;
        }

        if (!(obj instanceof CallPath)) {
            return false;
        }

        CallPath otherPath = (CallPath)obj;

        if (otherPath == this) {
            return true;
        }

        return new EqualsBuilder().
            append(_callPath, otherPath._callPath).
            append(_target, otherPath._target).
            isEquals();
    }

    public int hashCode() {
        return new HashCodeBuilder().
            append(_callPath).
            append(_target).
            toHashCode();
    }
}

