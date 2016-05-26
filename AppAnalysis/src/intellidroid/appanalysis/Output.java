package intellidroid.appanalysis;

class Output {

    public static void log(String output) {
        System.out.println(output);
    }

    public static void error(String output) {
        System.err.println(output);
    }

    public static void debug(boolean debugFlag, String output) {
        if (debugFlag) {
            System.out.println(output);
        }
    }

    public static void printPathInfo(String output) {
        if (IntelliDroidAppAnalysis.Config.PrintOutput) {
            System.out.println(output);
        }
    }

    public static void printConstraints(Predicate pred) {
        if (pred != null) {
            if (IntelliDroidAppAnalysis.Config.PrintConstraints) {
                pred.print(1);
            }
        }
    }
}

