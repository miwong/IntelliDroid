package intellidroid.appanalysis;

import com.ibm.wala.classLoader.*;
import com.ibm.wala.types.*;
import com.ibm.wala.ipa.cha.*;
import com.ibm.wala.ipa.callgraph.*;
import com.ibm.wala.ipa.callgraph.propagation.*;
import com.ibm.wala.util.config.AnalysisScopeReader;
import com.ibm.wala.util.graph.*;

import java.util.*;
import java.util.jar.JarFile;
import java.io.File;
import java.io.FileReader;
import java.io.BufferedReader;

import org.apache.commons.cli.*;
import org.apache.commons.io.FileUtils;
import java.text.ParseException;

public class IntelliDroidAppAnalysis {
    private final String _androidLib = "./android/android-4.3/android.jar";

    private enum TargetType {
        METHODS,
        NATIVE,
        REFLECTION
    }

    public static class Configuration {
        static public TargetType Target = TargetType.METHODS;
        static public Set<String> TargetMethods = new HashSet<String>();

        static public String AppDirectory = null;
        static public String AppName = null;
        static public String OutputDirectory = null;

        static public boolean PrintOutput = true;
        static public boolean PrintConstraints = false;
        static public boolean GenerateStats = false;
    }

    public static Configuration Config = new Configuration();

    public static void main(String[] args) throws Exception {
        Options options = new Options();
        options.addOption(
            Option.builder("o").longOpt("output")
                .required(false).hasArg(true)
                .desc("Output directory for extracted paths and constraints (default: \"./pathOutput\")")
                .build()
        );
        options.addOption(
            Option.builder("n").longOpt("name")
                .required(false).hasArg(true)
                .desc("Name of target application")
                .build()
        );
        options.addOption(
            Option.builder("s").longOpt("statistics")
                .required(false).hasArg(false)
                .desc("Produce output files containing static analysis statistics")
                .build()
        );
        options.addOption(
            Option.builder("x").longOpt("nostdout")
                .required(false).hasArg(false)
                .desc("Do not print extracted paths in standard output")
                .build()
        );
        options.addOption(
            Option.builder("y").longOpt("constraints")
                .required(false).hasArg(false)
                .desc("Print extracted constraints in standard output")
                .build()
        );
        options.addOption(
            Option.builder("h").longOpt("help")
                .required(false).hasArg(false)
                .desc("Print help")
                .build()
        );

        OptionGroup targetOptions = new OptionGroup();
        targetOptions.addOption(
            Option.builder("t").longOpt("targets")
                .required(false).hasArg(true)
                .desc("Input file listing target methods for analysis (default: \"./targetedMethods.txt\")")
                .build()
        );
        targetOptions.addOption(
            Option.builder("R").longOpt("reflection")
                .required(false).hasArg(false)
                .desc("Produce output to resolve reflection method calls")
                .build()
        );
        targetOptions.addOption(
            Option.builder("N").longOpt("native")
                .required(false).hasArg(false)
                .desc("Target native method invocations")
                .build()
        );

        options.addOptionGroup(targetOptions);

        CommandLineParser commandLineParser = new DefaultParser();

        try {
            CommandLine commands = commandLineParser.parse(options, args, true);

            if (commands.hasOption("h")) {
                throw new ParseException("Print help", 0);
            };

            List<String> operands = commands.getArgList();
            if (operands.size() != 1) {
                throw new ParseException("Missing target APK directory", 0);
            }

            Config.AppDirectory = operands.get(0);
            Config.AppName = commands.getOptionValue("n", null);
            Config.OutputDirectory = commands.getOptionValue("o", "./pathOutput");

            // Clean output directory
            try {
                File outputDirFile = new File(Config.OutputDirectory);
                outputDirFile.mkdirs();

                FileUtils.cleanDirectory(outputDirFile);
            } catch (Exception e) {
                Output.error(e.toString());
                e.printStackTrace();
            }

            if (commands.hasOption("x")) {
                Config.PrintOutput = false;
            };

            if (commands.hasOption("y")) {
                Config.PrintConstraints = true;
            };

            if (commands.hasOption("s")) {
                Config.GenerateStats = true;
            };

            if (commands.hasOption("R")) {
                Output.error("Reflection targeting currently not implemented yet.");
                return;
            } else if (commands.hasOption("N")) {
                Output.log("Target: native methods");
                Config.Target = TargetType.NATIVE;
            } else {
                String targetMethodsFile = commands.getOptionValue("t", "./targetedMethods.txt");
                Output.log("Target: " + targetMethodsFile);
                Config.Target = TargetType.METHODS;

                try {
                    BufferedReader br = new BufferedReader(new FileReader(targetMethodsFile));
                    String line;
                    while ((line = br.readLine()) != null) {
                        if (line.isEmpty() || line.startsWith("#")) {
                            continue;
                        }

                        String methodSignature = line.substring(line.indexOf("<") + 1, line.lastIndexOf(">"));
                        Config.TargetMethods.add(methodSignature);
                    }

                    br.close();

                } catch (Exception e) {
                    System.err.println("Cannot read target methods file");
                    System.err.println("Exception: " + e.toString());
                    return;
                }
            }
        } catch (ParseException e) {
            HelpFormatter helpFormatter = new HelpFormatter();
            helpFormatter.printHelp("IntelliDroidAppAnalysis [options] <directory path to extracted APK>", "output: extracted paths and constraints in \"--output\" directory", options, "\nUse the scripts in the \"preprocess\" directory to extract the target APK prior to running analysis", false);

            return;
        } catch (AlreadySelectedException e) {
            System.err.println("The target options (-t, -R, -N) are mutually exclusive and cannot be set at the same time.");
            return;
        }

        Output.log("Starting IntelliDroidAppAnalysis for " + (Config.AppName == null ? Config.AppDirectory : Config.AppName));
        IntelliDroidAppAnalysis analysis = new IntelliDroidAppAnalysis();
        analysis.analyze();
    }

    public IntelliDroidAppAnalysis() {
    }

    public void analyze() throws Exception {
        Statistics.startAnalysis();

        String appPath = null;
        String manifestPath = null;

        String extractedApkPath = Config.AppDirectory + "/apk";
        File extractedApkDir = new File(extractedApkPath);

        if (Config.AppName != null) {
            //appPath = Config.AppDirectory + "/" + Config.AppName + ".jar";
            //manifestPath = Config.AppDirectory + "/" + Config.AppName + ".xml";

            Output.error("\nDeprecated. Please use the preprocessing scripts.\n");
            return;

        } else if (extractedApkDir.isDirectory()) {
            appPath = extractedApkPath + "/classes.jar";
            manifestPath = extractedApkPath + "/AndroidManifest.xml";

        } else {
            Output.error("\nMissing AndroidManifest.xml and/or classes.jar files in target APK directory.");
            return;
        }

        ManifestAnalysis manifestAnalysis = new ManifestAnalysis(manifestPath);
        Output.log("Package: " + manifestAnalysis.getPackageName());

        // Represents code to be analyzed
        AnalysisScope appScope = AnalysisScopeReader.makeJavaBinaryAnalysisScope(appPath, null);
        Module androidMod = new JarFileModule(new JarFile(_androidLib));
        appScope.addToScope(ClassLoaderReference.Extension, androidMod);

        // A class hierarchy for name resolution, etc.
        IClassHierarchy appHierarchy = ClassHierarchy.make(appScope);

        if (Config.Target == TargetType.NATIVE) {
            Iterator<IClass> appClassIter = appHierarchy.getLoader(ClassLoaderReference.Application).iterateAllClasses();
            while (appClassIter.hasNext()) {
                IClass appClass = appClassIter.next();
                for (IMethod appMethod : appClass.getDeclaredMethods()) {
                    if (appMethod.isNative()) {
                        Config.TargetMethods.add(appMethod.getSignature());
                    }
                }
            }
        }

        Statistics.startCallGraph();

        CallGraphInfoListener callGraphInfoListener = new CallGraphInfoListener(appHierarchy);
        UIActivityMapping uiActivityAnalysis = new UIActivityMapping(appHierarchy);

        // Look for entrypoints and generate call graph
        EntrypointAnalysis entrypointAnalysis = new EntrypointAnalysis(
            appHierarchy, 
            manifestAnalysis, 
            uiActivityAnalysis, 
            callGraphInfoListener
        );

        Statistics.endCallGraph();

        Collection<IMethod> entrypoints = entrypointAnalysis.getEntrypoints();
        CallGraph callGraph = entrypointAnalysis.getCallGraph();
        PointerAnalysis pointerAnalysis = entrypointAnalysis.getPointerAnalysis();

        Statistics.setNumberOfNodes(callGraph.getNumberOfNodes());
        Statistics.setNumberOfEdges(GraphUtil.countEdges(callGraph));

        uiActivityAnalysis.setCallGraph(callGraphInfoListener, pointerAnalysis);

        Statistics.startConstraintAnalysis();

        // Analyze paths that lead to invocations of targeted methods
        TargetedPathsAnalysis targetedPathsAnalysis = new TargetedPathsAnalysis(
            entrypointAnalysis, 
            manifestAnalysis, 
            uiActivityAnalysis, 
            callGraphInfoListener
        );
        targetedPathsAnalysis.analyze();

        Statistics.endConstraintAnalysis();
        Statistics.endAnalysis();

        Statistics.writeToFile();
    }
}

