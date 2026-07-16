#!/bin/sh
# sks_configdb build script

SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
cd "$SCRIPT_DIR"

JDK_HOME=""

if [ -f "config.properties" ]; then
    JDK_HOME=$(grep "^jdk.home" "config.properties" | head -1 | sed 's/^jdk.home[[:space:]]*=[[:space:]]*//' | sed 's/[[:space:]]*$//')
fi

JAVAC_CMD="javac"
if [ -n "$JDK_HOME" ] && [ -f "$JDK_HOME/bin/javac" ]; then
    JAVAC_CMD="$JDK_HOME/bin/javac"
fi

echo "Compiling src/ConfigDBUI.java to bin/ ..."

mkdir -p bin

$JAVAC_CMD -encoding UTF-8 -cp "lib/snakeyaml-2.5.jar" -d "bin" src/ConfigDBUI.java
if [ $? -ne 0 ]; then
    echo ""
    echo "Build FAILED!"
    exit 1
fi

echo "Build successful - bin/ConfigDBUI.class"
