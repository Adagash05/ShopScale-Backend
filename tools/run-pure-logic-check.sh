#!/bin/sh
# Compiles the dependency-free classes (roles, permissions, insight rules, cost maths) and runs 100+ checks.
# Needs only a JDK 21. Works without Maven or the internet. Run from the backend folder.
set -e
S=src/main/java/com/amsati/shopscale
OUT=$(mktemp -d)
javac -d "$OUT" $S/security/Role.java $S/security/Permission.java $S/security/DefaultRoles.java \
  $S/insight/InsightRules.java $S/purchasing/CostMath.java tools/PureLogicHarness.java
java -cp "$OUT" PureLogicHarness
