#!/bin/bash
# Fetches the Scala 3.10.1 compiler sources and capture checking tests into
# work/, applies the patches, and builds the test driver.
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd); W=$HERE/work
. "$HERE/harness/env.sh"
mkdir -p "$W/src" "$W/driver"
if [ ! -d "$W/compiler-src" ]; then
  curl -sSfL -o "$W/compiler-src.jar" https://repo1.maven.org/maven2/org/scala-lang/scala3-compiler_3/3.10.1/scala3-compiler_3-3.10.1-sources.jar
  mkdir -p "$W/compiler-src" && (cd "$W/compiler-src" && unzip -q ../compiler-src.jar)
fi
if [ ! -d "$W/scala3/tests" ]; then
  git init -q "$W/scala3" && cd "$W/scala3"
  git remote add origin https://github.com/scala/scala3.git
  git config core.sparseCheckout true
  printf 'tests/neg-custom-args/captures/\ntests/pos-custom-args/captures/\ntests/run-custom-args/captures/\n' > .git/info/sparse-checkout
  git fetch -q --depth 1 origin refs/tags/3.10.1 && git checkout -q FETCH_HEAD
fi
for f in SepCheck CheckCaptures Setup; do
  cp "$W/compiler-src/dotty/tools/dotc/cc/$f.scala" "$W/src/$f.scala"
  patch -s "$W/src/$f.scala" "$HERE/patches/$f.diff"
done
java -Xmx1g -cp "$CCCOMP:$CCLIB" dotty.tools.dotc.Main -usejavacp -classpath "$CCCOMP:$CCLIB" \
  -d "$W/driver" -nowarn "$HERE/harness/Driver.scala"
echo "setup done"
