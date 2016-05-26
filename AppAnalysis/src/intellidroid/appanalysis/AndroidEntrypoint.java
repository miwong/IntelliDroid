package intellidroid.appanalysis;

import com.ibm.wala.classLoader.*;
import com.ibm.wala.types.*;
import com.ibm.wala.ipa.callgraph.impl.*;
import com.ibm.wala.ipa.cha.IClassHierarchy;
import com.ibm.wala.ssa.*;
import com.ibm.wala.analysis.typeInference.*;
import com.ibm.wala.shrikeBT.IInvokeInstruction;
import java.util.Collection;

class AndroidEntrypoint extends DefaultEntrypoint {

    AndroidEntrypoint(IMethod method, IClassHierarchy cha) {
        super(method, cha);
    }

    @Override
    protected int makeArgument(AbstractRootMethod m, int i) {
        TypeReference[] p = getParameterTypes(i);
        if (p.length == 0) {
            return -1;
        } else if (p.length == 1) {
            if (p[0].isPrimitiveType()) {
                return m.addLocal();
            } else {
                // ===== MODIFIED CODE FOR ANDROID FRAMEWORK =====
                SSANewInstruction n;

                // Add correct constructor for inner classes (to access outer class methods)
                if (isInnerClass(p[0])) {
                    n = addAllocationForInnerClass(p[0], m, m.addLocal());
                } else {
                    //n = m.addAllocation(p[0]);
                    n = addAllocationForParameterType(p[0], m);
                }

                // Add initialization for class (when initialization is done in systemReady and not in the constructor)
                /*
                IClass paramClass = m.getClassHierarchy().lookupClass(p[0]);
                IMethod systemReadyMethod = paramClass.getMethod(Selector.make("systemReady()V"));

                if (systemReadyMethod != null) {
                    SSAInvokeInstruction systemReadyInvoke = m.addInvocation(new int[] { n.getDef() }, CallSiteReference.make(m.getStatements().length, systemReadyMethod.getReference(), IInvokeInstruction.Dispatch.VIRTUAL));
                }
                */

                // ===== END OF MODIFICATIONS =====

                return (n == null) ? -1 : n.getDef();
            }
        } else {
            int[] values = new int[p.length];
            int countErrors = 0;
            for (int j = 0; j < p.length; j++) {
                // ===== MODIFIED CODE FOR ANDROID FRAMEWORK =====
                //SSANewInstruction n = m.addAllocation(p[j]);
                SSANewInstruction n;

                // Add correct constructor for inner classes (to access outer class methods)
                if (isInnerClass(p[j])) {
                    n = m.addAllocationWithoutCtor(p[j]);

                    for (IMethod ctor : m.getClassHierarchy().lookupClass(p[j]).getDeclaredMethods()) {
                        if (ctor.getSelector().toString().contains("<init>")) {
                            int[] params = new int[ctor.getNumberOfParameters() + 1];
                            params[0] = n.getDef();

                            for (int paramNum = 1; paramNum < params.length; paramNum++) {
                                params[paramNum] = m.addLocal();
                            }

                            m.addInvocation(params, CallSiteReference.make(m.getStatements().length, ctor.getReference(), IInvokeInstruction.Dispatch.SPECIAL));
                            break;
                        }
                    }
                } else {
                    //n = m.addAllocation(p[j]);
                    n = addAllocationForParameterType(p[j], m);
                }

                // Add initialization for class (when initialization is done in systemReady and not in the constructor)
                /*
                IMethod systemReadyMethod = m.getClassHierarchy().lookupClass(p[j]).getMethod(Selector.make("systemReady()V"));

                if (systemReadyMethod != null) {
                    SSAInvokeInstruction innerCtor = m.addInvocation(new int[] { n.getDef() }, CallSiteReference.make(m.getStatements().length, systemReadyMethod.getReference(), IInvokeInstruction.Dispatch.SPECIAL));
                }
                */
                // ===== END OF MODIFICATIONS =====

                int value = (n == null) ? -1 : n.getDef();
                if (value == -1) {
                    countErrors++;
                } else {
                    values[j - countErrors] = value;
                }
            }
            if (countErrors > 0) {
                int[] oldValues = values;
                values = new int[oldValues.length - countErrors];
                System.arraycopy(oldValues, 0, values, 0, values.length);
            }

            TypeAbstraction a;
            if (p[0].isPrimitiveType()) {
                a = PrimitiveType.getPrimitive(p[0]);
                for (i = 1; i < p.length; i++) {
                    a = a.meet(PrimitiveType.getPrimitive(p[i]));
                }
            } else {
                IClassHierarchy cha = m.getClassHierarchy();
                IClass p0 = cha.lookupClass(p[0]);
                a = new ConeType(p0);
                for (i = 1; i < p.length; i++) {
                    IClass pi = cha.lookupClass(p[i]);
                    a = a.meet(new ConeType(pi));
                }
            }

            return m.addPhi(values);
        }
    }

    private SSANewInstruction addAllocationForParameterType(TypeReference type, AbstractRootMethod m) {
        IClassHierarchy cha = m.getClassHierarchy();
        IClass pClass = cha.lookupClass(type);
        TypeReference paramAllocated = type;

        if (pClass != null && pClass.isAbstract()) {
            Collection<IClass> subClasses = cha.computeSubClasses(type);
            for (IClass subClass : subClasses) {
                if (!subClass.isAbstract() && !subClass.isInterface()) {
                    paramAllocated = subClass.getReference();
                    break;
                }
            }
        } else if (pClass != null && pClass.isInterface()) {
            Collection<IClass> subClasses = cha.getImplementors(type);
            for (IClass subClass : subClasses) {
                if (!subClass.isAbstract() && !subClass.isInterface()) {
                    paramAllocated = subClass.getReference();
                    break;
                }
            }
        }

        return m.addAllocation(paramAllocated);
    }

    private boolean isInnerClass(TypeReference type) {
        return (type.getName().toString().indexOf("$") > -1);
    }

    private SSANewInstruction addAllocationForInnerClass(TypeReference type, AbstractRootMethod m, int outerInstance) {
        SSANewInstruction n = m.addAllocationWithoutCtor(type);

        for (IMethod ctor : m.getClassHierarchy().lookupClass(type).getDeclaredMethods()) {
            if (ctor.getSelector().toString().contains("<init>")) {
                int[] params = new int[ctor.getNumberOfParameters()];

                if (params.length > 0) {
                    params[0] = n.getDef();
                }

                if (params.length > 1) {
                    params[1] = outerInstance;
                }

                for (int paramNum = 2; paramNum < params.length; paramNum++) {
                    params[paramNum] = m.addLocal();

                    TypeReference paramType = ctor.getParameterType(paramNum);

                    if (isInnerClass(paramType)) {
                        //SSANewInstruction paramInnerNew = addAllocationForInnerClass(paramType, m, outerInstance);
                        //params[paramNum] = paramInnerNew.getDef();
                        SSANewInstruction paramNew = m.addAllocation(paramType);
                        params[paramNum] = paramNew.getDef();
                    } else if (!paramType.isPrimitiveType()) {
                        SSANewInstruction paramNew = m.addAllocation(paramType);
                        if (paramNew == null) {
                            // Handle non-concrete types
                            params[paramNum] = m.addLocal();
                        } else {
                            params[paramNum] = paramNew.getDef();
                        }
                    } else {
                        params[paramNum] = m.addLocal();
                    }
                }

                m.addInvocation(params, CallSiteReference.make(m.getStatements().length, ctor.getReference(), IInvokeInstruction.Dispatch.SPECIAL));
                break;
            }
        }

        return n;
    }
}

