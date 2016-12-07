#IntelliDroid

IntelliDroid is an analysis tool for Android applications that extracts call paths leading to specific behavior and executes these paths precisely during run time.  When given a set of *targeted behaviors*, the static analysis component traverses the application's call graph to find paths to these behaviors.  It also extracts path constraints, which are used to determine the input values that can trigger these paths.  The dynamic component takes the extracted paths/constraints and injects the input values into the Android device, triggering the targeted behaviors.  

For further details, please see our [paper](http://www.eecg.toronto.edu/~lie/papers/mwong_ndss2016.pdf) and [slides](http://miwong.me/files/intellidroid_ndss2016_slides.pdf) (NDSS 2016).

##Components

* [Framework Analysis](#framework-analysis)
* [App Analysis](#app-analysis)
* [Dynamic Client](#dynamic-client)


##Framework Analysis 

The 'FrameworkAnalysis' performs static analysis to generate constraints for the Android framework.  We currently provide the output framework constraints so that they can be added to the application constraints produced by the 'AppAnalysis' component.  


##App Analysis

The 'AppAnalysis' directory holds the code that generate constraints for Android applications.

####Contents: 

| Directory   | Description                                                                                | 
|:------------|:-------------------------------------------------------------------------------------------| 
| preprocess  | Scripts to extract and preprocess APK file before passing it to the tool.                  | 
| src         | Source code files.                                                                         | 
| libs        | Dependencies, including the necessary WALA libraries.<sup>1</sup>                          | 
| android     | Compiled Android framework files (to be analyzed), from AOSP version 4.4.2_r2.             | 

<sub><sup>1</sup> We have made slight changes to WALA's call graph generation to improve IntelliDroid's performance.  The modified source code can be found [here](https://github.com/miwong/WALA/tree/R_1.3.6_ANDROID_LISTENER).</sub>  

####Building and running

This project uses the Gradle build system.  Output files are located in the `build/` directory.  The gradlew script is a wrapper for machines that do not have Gradle already installed.  If your development machine already contains Gradle, you can use your own installation by replacing `./gradlew` with `gradle` in the commands below.  A network connection is required when compiling the code for the first time so that Gradle can automatically download dependencies.  

The included Android framework files in the `android` directory were compiled using JDK 1.6 but the `apktool` used in the preprocessing scripts require JDK 1.7.  We recommend using JDK 1.7, as that seems to work best.  If necessary, you can replace the files in the `android` directory to try different versions of AOSP or JDK.  

#####Preprocessing APK files: 

The target APK file to be analyzed should first be preprocessed using the scripts in the `preprocess` folder.  The resulting directory (containing the APK file and the extracted resources) can then be passed to the static analysis.  

The preprocessing scripts uses [Apktool](http://ibotpeaches.github.io/Apktool/) and [Dare](http://siis.cse.psu.edu/dare/) to extract the APK package.  You can use your own extraction tools, but you may have to modify the app analysis code so that IntelliDroid can find the bytecode and manifest files for a given application.  
  
    ./preprocess/PreprocessAPK.sh <APK file>
    ./preprocess/PreprocessDataset.sh <directory of APK files>

#####To build: 
    ./gradlew build  

#####To build and run: 
    ./IntelliDroidAppAnalysis -o <output directory> <preprocessed app directory>
  
  
To see other command-line options, run:  

    ./IntelliDroidAppAnalysis --help
  
  
The output directory is used to store the app info JSON file and the Z3 constraint files.  If not specified, these files will be stored in `./pathOutput`.  The output files are used by the `IntelliDroidDynamicClient` tool to identify the call paths and to generate the input data to trigger these paths.  

The `appInfo.json` and `constraintX_X.py` files produced in the output directory are necessary for the dynamic client, but are not very readable.  The `-y` flag can be used to obtain a more readable (but output-heavy) version of the path/constraint results (printed in stdout).  


##Dynamic Client

The 'DynamicClient' directory contains the python program that communicates with the Android device and sends the inputs that trigger the desired events.  

####Setting up the environment

The program expects that an Android device or emulator is connected to the system.  This device must be running the custom Android OS containing IntelliDroidService (which interprets the commands this program sends and does the actual event invocation).  The DynamicClient program will automatically connect to the device via a socket on port tcp:12348.  The `adb` tool will be used to set up the connection and run certain commands, and it should be reachable from `PATH` (this can be done by adding the Android SDK tools directory to the `PATH` variable or by setting up the AOSP build environment prior to running the DynamicClient.  

In addition, the DynamicClient uses the Z3 constraint solver via its Python API (z3-py).  The instructions to build and install Z3 with Python bindings are available [here](https://github.com/Z3Prover/z3).  

####Patching IntelliDroidService

The custom Android OS used by IntelliDroid is provided as a series of diff files that can be applied to the base AOSP source tree.  IntelliDroid is currently implemented for Android 4.3 (AOSP branch `android-4.3_r1`).  Please refer to the [AOSP documentation](https://source.android.com/source/requirements.html) for instructions on how to download and build Android.  

Once you have downloaded and compiled the correct AOSP version, you can apply the IntelliDroid patches, which are located in the `androidPatches` directory.  The `patch.sh` and `unpatch.sh` files are provided to help automate this process.  After patching, rebuild AOSP (we've found that you might have to run `make` twice; otherwise, certain files on the emulator become inconsistent).  You may also need to run `make update-api` along with `make`.  Once built, you can use `logcat` to verify that the `IntelliDroidService` class is started as a system service when the device boots.  

#####To apply patches:
    cd androidPatches
    ./patch.sh <path to AOSP directory>

####Running

#####To run: 
    ./IntelliDroidDynamicClient.py

#####Commands:

`HELP` (get description of commands)  
`INSTALL <APK file>`  
`START <directory to app information, generated by IntelliDroidAppAnalysis>`  
`TRIGGER <call path ID to trigger, as specified in appInfo.json>`  
`EXECUTE <command to send to IntelliDroidService>`<sup>1</sup>  
`INFO <info requested from IntelliDroidService>`<sup>1</sup>  
`CLOSE`  
`KILL`  

<sub><sup>1</sup> These commands are for debugging purposes only.  You should only use these if you know the exact parameters that IntelliDroidService expects.</sub>  


##TaintDroid Integration

Integration with TaintDroid is fairly straightforward.  For the 'AppAnalysis' component, use the `-t` flag to specify a different list of targeted methods (i.e. the provided `taintdroidTargets.txt` file).

    ./IntelliDroidAppAnalysis -t taintdroidTargets.txt <preprocessed app directory>

On the dynamic side, follow the instructions [here](http://www.appanalysis.org/download.html) to download and build TaintDroid for Android 4.3.  Once you have verified that your build of TaintDroid works, apply the patches in `DynamicClient/androidPatches` in the same way as you would for unmodified AOSP.  


##Contact

IntelliDroid was initially developed as a Master's thesis project by Michelle Wong at the University of Toronto, supervised by Dr. David Lie.  
  
For any inquiries, please contact:
* Michelle Wong (michelley.wong@mail.utoronto.ca)

The following have contributed code to IntelliDroid:
* Michelle Wong
* Dr. David Lie
* Mariana D'Angelo
* Peter Sun

##License

IntelliDroid is released under the [MIT License](https://opensource.org/licenses/MIT).  

