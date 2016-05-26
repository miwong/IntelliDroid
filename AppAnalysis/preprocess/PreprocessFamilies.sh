#!/bin/bash

PREPROCESS_DATASET=$(dirname $0)/PreprocessDataset.sh

APK_DIR=$1

for APK_FAMILY in $APK_DIR/*
do
    if [ -d "${APK_FAMILY}" ]; then
        ${PREPROCESS_DATASET} ${APK_FAMILY}
    fi
done

