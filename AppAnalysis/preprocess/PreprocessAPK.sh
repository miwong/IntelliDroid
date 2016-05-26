#!/bin/bash

source $(dirname $0)/IntelliDroidPreprocessAPK.sh

APK=$1
preprocessAPK ${APK}
