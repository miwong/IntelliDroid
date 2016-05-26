package intellidroid.appanalysis;

import java.util.*;
import java.io.FileWriter;
import java.io.PrintWriter;

import com.ibm.wala.classLoader.*;
import com.ibm.wala.ipa.callgraph.*;
import com.ibm.wala.util.collections.*;

class Statistics {
    static private Date _startTime = null;
    static private Date _endTime = null;

    static private Date _callGraphStartTime = null;
    static private Date _callGraphEndTime = null;

    static private Date _constraintStartTime = null;
    static private Date _constraintEndTime = null;

    static private long _numberOfNodes = 0;
    static private long _numberOfEdges = 0;
    static private Set<CGNode> _pathNodes = new HashSet<CGNode>();
    static private Set<Pair<IMethod, IMethod>> _pathEdges = new HashSet<Pair<IMethod, IMethod>>();

    //=========================================================================

    static public void startAnalysis() {
        if (IntelliDroidAppAnalysis.Config.GenerateStats) {
            _startTime = new Date();
        }
    }

    static public void endAnalysis() {
        if (IntelliDroidAppAnalysis.Config.GenerateStats) {
            _endTime = new Date();
        }
    }

    static public void startCallGraph() {
        if (IntelliDroidAppAnalysis.Config.GenerateStats) {
            _callGraphStartTime = new Date();
        }
    }

    static public void endCallGraph() {
        if (IntelliDroidAppAnalysis.Config.GenerateStats) {
            _callGraphEndTime = new Date();
        }
    }

    static public void startConstraintAnalysis() {
        if (IntelliDroidAppAnalysis.Config.GenerateStats) {
            _constraintStartTime = new Date();
        }
    }

    static public void endConstraintAnalysis() {
        if (IntelliDroidAppAnalysis.Config.GenerateStats) {
            _constraintEndTime = new Date();
        }
    }

    static public void setNumberOfNodes(long num) {
        if (IntelliDroidAppAnalysis.Config.GenerateStats) {
            _numberOfNodes = num;
        }
    }

    static public void setNumberOfEdges(long num) {
        if (IntelliDroidAppAnalysis.Config.GenerateStats) {
            _numberOfEdges = num;
        }
    }

    static public void trackPath(List<CGNode> path, IMethod targetMethod) {
        if (IntelliDroidAppAnalysis.Config.GenerateStats) {
            _pathNodes.addAll(path);

            for (int i = 0; i < path.size() - 1 ; i++) {
                _pathEdges.add(Pair.make(path.get(i).getMethod(), path.get(i + 1).getMethod()));
            }

            if (targetMethod != null) {
                _pathEdges.add(Pair.make(path.get(path.size() - 1).getMethod(), targetMethod));
            }
        }
    }

    //=========================================================================

    static public void writeToFile() {
        if (IntelliDroidAppAnalysis.Config.GenerateStats) {
            try {
                // Print timing information
                FileWriter fileWriter = new FileWriter("./timingStats.csv", true);
                PrintWriter timeWriter = new PrintWriter(fileWriter);
                String timingStr = IntelliDroidAppAnalysis.Config.AppDirectory + "," + getTotalTime() + "," + getCallGraphTime() + "," + getConstraintAnalysisTime();
                timeWriter.println(timingStr);
                timeWriter.close();
                fileWriter.close();

                // Print static analysis statitics 
                FileWriter statsFileWriter = new FileWriter("./staticStats.csv", true);
                PrintWriter statsWriter = new PrintWriter(statsFileWriter);
                String statsStr = IntelliDroidAppAnalysis.Config.AppDirectory + "," + _numberOfNodes + "," + _numberOfEdges + "," + _pathNodes.size() + "," + _pathEdges.size();
                statsWriter.println(statsStr);
                statsWriter.close();
                statsFileWriter.close();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    //=========================================================================

    static private long getTotalTime() {
        return _endTime.getTime() - _startTime.getTime();
    }

    static private long getCallGraphTime() {
        return _callGraphEndTime.getTime() - _callGraphStartTime.getTime();
    }

    static private long getConstraintAnalysisTime() {
        return _constraintEndTime.getTime() - _constraintStartTime.getTime();
    }

    static private long getNumberOfNodes() {
        return _numberOfNodes;
    }

    static private long getNumberOfEdges() {
        return _numberOfEdges;
    }

    static private long getNumberOfPathNodes() {
        return _pathNodes.size();
    }

    static private long getNumberOfPathEdges() {
        return _pathEdges.size();
    }

}

