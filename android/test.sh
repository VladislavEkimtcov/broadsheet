#!/usr/bin/env bash
# Runs the shared behaviour cases (../spec/cases/*.json) against the Android app's plain-Java
# code on the computer: Sun, Dates, Ladder, Sse. windows/tests/Tests.cs reads the same files.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
if [[ -z ${JAVA_HOME:-} || ! -x ${JAVA_HOME:-}/bin/javac ]]; then
  for j in /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home /usr/lib/jvm/java-17-openjdk \
           "$(/usr/libexec/java_home -v 17 2>/dev/null || true)"; do
    if [[ -n $j && -x $j/bin/javac ]]; then JAVA_HOME=$j; break; fi
  done
fi
JAVAC=${JAVA_HOME:+$JAVA_HOME/bin/}javac
JAVA=${JAVA_HOME:+$JAVA_HOME/bin/}java
P=src/io/github/vladislavekimtcov/broadsheet
rm -rf build/test
"$JAVAC" -d build/test $P/Sun.java $P/Dates.java $P/Ladder.java $P/Sse.java $P/Updates.java $P/FeedLayout.java tests/CasesTest.java
"$JAVA" -cp build/test io.github.vladislavekimtcov.broadsheet.CasesTest ../spec/cases
