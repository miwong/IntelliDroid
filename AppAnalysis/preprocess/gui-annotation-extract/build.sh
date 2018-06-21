#!/bin/bash
set -e
cd "$(dirname $0)"
rm -r bin || true
mkdir bin
find src -name "*.java" | xargs -- javac -classpath "src:lib/*" -d bin
