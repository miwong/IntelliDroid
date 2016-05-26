#!/bin/bash

DARE=$(dirname $0)/dare-1.1.0-linux/dare

if [ "$(uname)" == "Darwin" ]; then
    DARE=$(dirname $0)/dare-1.1.0-macos/dare
fi

APKTOOL=$(dirname $0)/apktool-2.0.0rc4/apktool

preprocessAPK() {
    # Expand the APK file path (only works in Linux)
    if [ "$(uname)" == "Darwin" ]; then
        apkFile=$(greadlink -f $1)
    else
        apkFile=$(readlink -f $1)
    fi

    apkDir=$(dirname "$apkFile")

    # Extract app name from APK file
    appName=$(basename "$apkFile")
    appName="${appName%.*}"

    apkDir=${apkDir}/${appName}

    # Create output directory and place APK file in there
    mkdir ${apkDir}.dir
    mv $apkFile ${apkDir}.dir/${appName}.apk
    mv ${apkDir}.dir ${apkDir}

    apkFile=${apkDir}/${appName}.apk
    extractedApkDir=${apkDir}/apk
    dareDir=${apkDir}/dare

    # Use apktool to expand the APK file and copy the manifest
    ${APKTOOL} d -o ${extractedApkDir} -s ${apkFile}
    #cp ${extractedApkDir}/AndroidManifest.xml ${apkDir}/AndroidManifest.xml

    # Use dare to convert Dex bytecode to Java bytecode and place class files into jar
    ${DARE} -d ${dareDir} ${apkFile}
    jar cf ${extractedApkDir}/classes.jar -C ${dareDir}/retargeted/${appName} .
    rm -r ${dareDir}
}

preprocessAPKDir() {
    # Expand the APK directory path (only works in Linux)
    if [ "$(uname)" == "Darwin" ]; then
        apkFile=$(greadlink -f $1)
    else
        apkDir=$(readlink -f $1)
    fi

    # Extract app name from APK directory
    appName=$(basename "$apkDir")

    extractedApkDir=${apkDir}/apk

    if [[ ! -e "${extractedApkDir}" ]] || [[ ! -e "${extractedApkDir}/classes.jar" ]]; then
        apkFile=${apkDir}/${appName}.apk
        dareDir=${apkDir}/dare

        # Use apktool to expand the APK file and copy the manifest
        ${APKTOOL} d -f -o ${extractedApkDir} -s ${apkFile}
        #cp ${extractedApkDir}/AndroidManifest.xml ${apkDir}/AndroidManifest.xml

        # Use dare to convert Dex bytecode to Java bytecode and place class files into jar
        ${DARE} -d ${dareDir} ${apkFile}
        jar cf ${extractedApkDir}/classes.jar -C ${dareDir}/retargeted/${appName} .
        rm -r ${dareDir}
    fi
}
