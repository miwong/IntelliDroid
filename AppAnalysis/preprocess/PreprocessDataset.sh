#!/bin/bash

source $(dirname $0)/IntelliDroidPreprocessAPK.sh

APK_DIR=$1

for APK in ${APK_DIR}/*
do
    #APK_PATH=$(readlink -f "$APK")

    if [ -f "${APK}" ]; then
        preprocessAPK ${APK}
    elif [ -d "$APK" ]; then
        preprocessAPKDir ${APK}
    fi
done
