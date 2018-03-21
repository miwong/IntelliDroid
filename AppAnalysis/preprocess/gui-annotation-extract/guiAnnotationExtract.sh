#!/bin/bash
set -e
$(dirname $0)/build.sh
exec java -cp "$(dirname $0)/bin:$(dirname $0)/lib/*" intellidroid.appanalysis.guiannotations.GUIAnnotationExtract $@
