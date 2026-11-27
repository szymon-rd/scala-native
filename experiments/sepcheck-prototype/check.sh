#!/bin/bash
# Builds the patched compiler classes, then runs the hole suite and the
# Scala 3 capture checking tests with and without the patches.
set -uo pipefail
HERE=$(cd "$(dirname "$0")" && pwd); W=$HERE/work; H=$HERE/harness
. "$H/env.sh"
rm -rf "$W/classes" && mkdir -p "$W/classes" "$W/out"
java -Xss16m -Xmx2g -cp "$CCCOMP:$CCLIB" dotty.tools.dotc.Main -usejavacp -classpath "$CCCOMP:$CCLIB" \
  -d "$W/classes" -nowarn -Yexplicit-nulls -language:implicitConversions "$W"/src/*.scala || { echo BUILD FAILED; exit 1; }
run() { # <jobs> <classes|-> <results>
  CP="$CCCOMP:$CCLIB"; [ "$2" != "-" ] && CP="$2:$CP"
  java -Xss16m -Xmx3g -cp "$W/driver:$CP" CCDriver "$1" "$CCLIB" "$3"
}
T=$W/scala3/tests
python3 -I "$H/genjobs.py" "$W/out/suite.jobs" "$W/out/suite.expected" sneg="$HERE/suite/neg" spos="$HERE/suite/pos"
python3 -I "$H/genjobs.py" "$W/out/dotty.jobs" "$W/out/dotty.expected" pos="$T/pos-custom-args/captures" neg="$T/neg-custom-args/captures" pos="$T/run-custom-args/captures"
run "$W/out/suite.jobs" - "$W/out/suite-base.results"
run "$W/out/suite.jobs" "$W/classes" "$W/out/suite.results"
run "$W/out/dotty.jobs" - "$W/out/dotty-base.results"
run "$W/out/dotty.jobs" "$W/classes" "$W/out/dotty.results"
echo "== hole suite, unpatched";  python3 -I "$H/compare.py" "$W/out/suite.expected" "$W/out/suite-base.results"
echo "== hole suite, patched";    python3 -I "$H/compare.py" "$W/out/suite.expected" "$W/out/suite.results" "$W/out/suite-base.results"
echo "== Scala 3 capture checking tests, patched vs unpatched"
python3 -I "$H/compare.py" "$W/out/dotty.expected" "$W/out/dotty.results" "$W/out/dotty-base.results"

# Mechanical drop insertion driven by what separation checking exported.
echo "== drop insertion demo (patched checker + ownership-drops plugin)"
P=$W/dropplugin && rm -rf "$P" "$W/demo" && mkdir -p "$P" "$W/demo"
java -Xss16m -cp "$W/classes:$CCCOMP:$CCLIB" dotty.tools.dotc.Main -usejavacp -classpath "$W/classes:$CCCOMP:$CCLIB" \
  -d "$P" -nowarn "$HERE/dropphase/DropPlugin.scala" || { echo "PLUGIN BUILD FAILED (rerun setup.sh?)"; exit 1; }
cp "$HERE/dropphase/plugin.properties" "$P/" && (cd "$P" && jar cf ../dropplugin.jar .)
java -Xss16m -cp "$W/classes:$CCCOMP:$CCLIB" dotty.tools.dotc.Main -usejavacp -classpath "$CCLIB" \
  -Xplugin:"$W/dropplugin.jar" -d "$W/demo" "$HERE/dropdemo/Rt.scala" "$HERE/dropdemo/Demo.scala" \
  || { echo "DEMO BUILD FAILED"; exit 1; }
java -cp "$W/demo:$CCLIB" Main
