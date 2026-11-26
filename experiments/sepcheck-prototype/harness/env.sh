# Classpaths of the Scala 3.10.1 compiler and library, from the coursier cache.
# Run `cs fetch org.scala-lang:scala3-compiler_3:3.10.1` (or build any 3.10.1
# project with sbt) first if these jars are missing.
C=${COURSIER_CACHE:-$HOME/.cache/coursier/v1}/https/repo1.maven.org/maven2
A=$C/org/ow2/asm
export CCLIB="$C/org/scala-lang/scala-library/3.10.1/scala-library-3.10.1.jar:$C/org/scala-lang/scala3-library_3/3.10.1/scala3-library_3-3.10.1.jar"
export CCCOMP="$C/org/scala-lang/scala3-compiler_3/3.10.1/scala3-compiler_3-3.10.1.jar:$C/org/scala-lang/scala3-interfaces/3.10.1/scala3-interfaces-3.10.1.jar:$C/org/scala-lang/tasty-core_3/3.10.1/tasty-core_3-3.10.1.jar:$C/org/scala-lang/modules/scala-asm/9.9.0-scala-1/scala-asm-9.9.0-scala-1.jar:$C/org/scala-sbt/compiler-interface/1.12.1/compiler-interface-1.12.1.jar:$A/asm/9.11.1/asm-9.11.1.jar:$A/asm-tree/9.11.1/asm-tree-9.11.1.jar:$A/asm-commons/9.11.1/asm-commons-9.11.1.jar:$A/asm-analysis/9.11.1/asm-analysis-9.11.1.jar:$A/asm-util/9.11.1/asm-util-9.11.1.jar"
