#!/bin/sh
# Validates the WearLink UKEY2 responder against the real google/ukey2 library (as initiator).
# Needs: the vendored JDK + gradle + arm64 protoc, and research/ukey2-src (git clone of google/ukey2,
# pinned 10fc737). All gitignored. See docs/UKEY2_IMPL.md.
set -e
ROOT=$(cd "$(dirname "$0")/.." && pwd)
export JAVA_HOME="$ROOT/tools/jdk/Contents/Home"
export PATH="$ROOT/tools/protoc-dl:$PATH"
UK="$ROOT/research/ukey2-src"
[ -d "$UK" ] || { echo "missing $UK (clone google/ukey2 there)"; exit 1; }
GRADLE="$ROOT/tools/gradle-8.11.1/bin/gradle"
( cd "$UK" && "$GRADLE" compileJava -q --console=plain )
CP=$(cd "$UK" && "$GRADLE" printRuntimeClasspath -q --console=plain | grep CLASSPATH= | sed 's/CLASSPATH=//')
export UKEY2_ORACLE_CP="$CP"
cd "$ROOT/WearLink"
swift test --filter UKey2OracleTests
