#!/bin/zsh
# Builds runwayd as a normal double-click Mac app, wrapped in a .dmg you can send.
#
#   ./package-mac.sh
#   → dist/runwayd-1.0.0.dmg
#
# What's inside: the app, plus its own copy of Java (the person you send it to doesn't need Java).
# No budgets and no personal data. On a Mac that has never run it, the app opens an empty budget
# with a hint to try Budget → Open the demo budget. A Mac that already has data (~/runwayd, or the
# older ~/Paydar or ~/TreasureTracker) keeps it. To give someone a budget, use Budget → Share
# (one budget for both of you) or Budget → Export (a copy).
#
# It's built for Apple-chip Macs (M1 and later), the same kind as the Mac running this.
set -euo pipefail

cd "$(dirname "$0")"
export JAVA_HOME=$(ls -d ~/tools/jdk-21*/Contents/Home)
export PATH="$JAVA_HOME/bin:$HOME/tools/apache-maven-3.9.9/bin:$PATH"

INPUT=desktop/target/package-input   # everything that goes in runwayd.app/Contents/app/
DIST=dist

# 1. Build and test both modules.
mvn -q -B install

# 2. Gather the app's jars: desktop, core, Jackson and JavaFX (the Mac version). Nothing else.
rm -rf "$INPUT" "$DIST"
mkdir -p "$INPUT" "$DIST"
mvn -q -B -pl desktop dependency:copy-dependencies -DincludeScope=runtime -DoutputDirectory="$PWD/$INPUT"
cp desktop/target/desktop-*.jar "$INPUT/desktop.jar"

# 3. Safety check: only jars go in, never a data file.
if [[ -n "$(find "$INPUT" -type f ! -name '*.jar')" ]]; then
    echo "Stopping: something other than the app's jars is in $INPUT:" >&2
    find "$INPUT" -type f ! -name '*.jar' >&2
    exit 1
fi

# 4. Package it. jpackage comes with the JDK; it adds a trimmed-down Java to the app.
jpackage \
    --type dmg \
    --name runwayd \
    --app-version 1.0.0 \
    --input "$INPUT" \
    --main-jar desktop.jar \
    --main-class com.finance.runwayd.desktop.Launcher \
    --java-options "-Dfile.encoding=UTF-8" \
    --dest "$DIST"

echo "Done: $(ls "$DIST"/*.dmg)"
