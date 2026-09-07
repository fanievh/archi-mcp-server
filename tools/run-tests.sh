#!/usr/bin/env bash
#
# Headless build + test harness for arch-mcp-server.
#
# ONE command that, on a machine with a local Archi 5.7 install:
#   1. compiles BOTH projects FROM SOURCE with javac against the assembled Archi/Eclipse/lib
#      classpath (proven cleaner than the Eclipse PDE build, which error-stubs EMF refs),
#   2. auto-discovers the test class list by scanning the tests source tree (no hard-coded list —
#      kills the stale 49-class AllPluginTestsRunner array, which went stale silently),
#   3. runs the headless-safe classes via a JUnit4 runner that asserts testsRun > 0 per class
#      (the silent-stale guard AllPluginTestsRunner lacked) and emits surefire JUnit XML,
#   4. exits non-zero if any (non-quarantined) test fails or any class contributes zero tests.
#
# Generalizes the proven run-pins.sh classpath assembly. Designed so m0-2 (GitHub Actions) can call
# this identical script on Linux — all locations are env vars, no macOS-only assumptions in the
# default code path (no -XstartOnFirstThread unless --swt is requested).
#
# Usage:
#   tools/run-tests.sh                 # compile + run all headless-safe classes (scan ∖ manifest)
#   tools/run-tests.sh ClassA ClassB   # compile + run ONLY these classes (FQCN or bare name —
#                                      #   a bare name is resolved against the test source tree;
#                                      #   unknown/ambiguous names are rejected before the compile)
#   tools/run-tests.sh --swt [Class…]  # add -XstartOnFirstThread (macOS SWT classes); run subset
#   tools/run-tests.sh --release       # THE RELEASE GATE: both passes, one compile.
#                                      #   pass 1 = the headless scan above
#                                      #   pass 2 = the manifest's "Group B" display classes,
#                                      #            with a display (-XstartOnFirstThread on macOS)
#                                      # Red if EITHER pass is red. Use this before shipping.
#
# WHY --release EXISTS
#   The default pass subtracts tools/osgi-excluded-tests.txt, which removes the display-required
#   "Group B" classes. Those classes are real gate coverage — they hold the only executable pins
#   for the accessor facade — so a green default pass is NOT a green release. CI already runs them
#   in a separate `ci-pde` lane; this flag gives the dev box the same two-lane coverage in one
#   command, so a regression cannot reach a commit just because the local gate never ran the class.
#   Group A (OSGi/Assume-only) stays excluded from BOTH passes: it cannot run outside Eclipse PDE.
#
# Env vars (with macOS dev-box defaults):
#   ARCHI_HOME    Archi's Eclipse dir   (default /Applications/Archi.app/Contents/Eclipse)
#   ECLIPSE_HOME  Eclipse IDE dir       (default /Applications/Eclipse.app/Contents/Eclipse)  [JUnit/Hamcrest]
#   M2_REPO       Maven local repo      (default ~/.m2/repository)                            [Mockito stack]
#   BUILD_DIR     compile output dir    (default <repo>/build/test-harness)  — NOT the Eclipse bin/
#   RESULTS_DIR   JUnit XML output dir  (default <repo>/build/test-results)  — surefire convention
#                 Cleared of TEST-*.xml at the start of every run so the dir describes exactly one
#                 invocation; a census over it cannot mistake a leftover file for current coverage.
#
set -uo pipefail

# ---- locate repo (the script lives in <repo>/tools/) ----
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$SCRIPT_DIR/.." && pwd)"
PROD="$REPO/net.vheerden.archi.mcp"
TESTS="$REPO/net.vheerden.archi.mcp.tests"

ARCHI_HOME="${ARCHI_HOME:-/Applications/Archi.app/Contents/Eclipse}"
ECLIPSE_HOME="${ECLIPSE_HOME:-/Applications/Eclipse.app/Contents/Eclipse}"
M2_REPO="${M2_REPO:-$HOME/.m2/repository}"
BUILD_DIR="${BUILD_DIR:-$REPO/build/test-harness}"
RESULTS_DIR="${RESULTS_DIR:-$REPO/build/test-results}"

EXCLUDED_MANIFEST="$SCRIPT_DIR/osgi-excluded-tests.txt"
KNOWN_FAILING="$SCRIPT_DIR/known-failing-tests.txt"
RUNNER_CLASS="net.vheerden.archi.mcp.harness.HeadlessTestRunner"

die() { echo "ERROR: $*" >&2; exit 2; }

# ---- parse args: --swt flag + optional explicit class names ----
SWT=0
RELEASE=0
EXPLICIT_CLASSES=()
for arg in "$@"; do
  case "$arg" in
    --swt) SWT=1 ;;
    --release) RELEASE=1 ;;
    -h|--help) awk 'NR==1{next} /^#/{sub(/^# ?/,""); print; next} {exit}' "$0"; exit 0 ;;
    -*) die "unknown flag: $arg (supported: --swt, --release)" ;;
    *) EXPLICIT_CLASSES+=("$arg") ;;
  esac
done
# --release is the whole gate by definition; naming classes as well is a contradiction, and
# silently honouring one over the other would under-run the gate the caller asked for.
if [ "$RELEASE" -eq 1 ] && [ "${#EXPLICIT_CLASSES[@]}" -gt 0 ]; then
  die "--release runs the full two-pass gate and cannot be combined with explicit class names"
fi

# ---- validate required paths (each failure names WHICH path is missing) ----
ARCHI_PLUGINS="$ARCHI_HOME/plugins"
ECLIPSE_PLUGINS="$ECLIPSE_HOME/plugins"
[ -d "$ARCHI_PLUGINS" ]   || die "ARCHI_HOME/plugins not found: $ARCHI_PLUGINS (set ARCHI_HOME to your Archi 5.7 Eclipse dir)"
[ -d "$ECLIPSE_PLUGINS" ] || die "ECLIPSE_HOME/plugins not found: $ECLIPSE_PLUGINS (needed for JUnit + Hamcrest; set ECLIPSE_HOME)"
[ -d "$M2_REPO" ]         || die "M2_REPO not found: $M2_REPO (needed for the Mockito stack; set M2_REPO)"
[ -d "$PROD/src" ]        || die "production source not found: $PROD/src"
[ -d "$TESTS/src" ]       || die "test source not found: $TESTS/src"
command -v javac >/dev/null || die "javac not on PATH (need a JDK 21+)"
command -v java  >/dev/null || die "java not on PATH (need a JDK 21+)"
# The exclusion manifest is a required checked-in artifact in scan mode (the single exclusion SoT).
# Validate it here so a missing/unreadable manifest fails with a named-path message rather than
# surfacing later as confusing per-class testsRun>0 violations on the OSGi classes.
if [ "${#EXPLICIT_CLASSES[@]}" -eq 0 ]; then
  [ -f "$EXCLUDED_MANIFEST" ] || die "excluded-class manifest not found: $EXCLUDED_MANIFEST (the exclusion source of truth — it should be checked in)"
fi

# ---- resolve + validate explicitly-named classes (BEFORE the compile, so a bad argument costs
# ---- a second rather than a full two-project build) ----
# The runner takes plain FQCNs (Class.forName), so a bare class name or a typo would otherwise
# reach it as an unloadable name and surface as "ran=0 + testsRun>0 VIOLATION" — a red run that
# reads like a broken test rather than a bad argument, and one that writes a stale XML under the
# wrong filename. Resolve bare names to FQCNs; reject anything that does not exist, by name.
if [ "${#EXPLICIT_CLASSES[@]}" -gt 0 ]; then
  RESOLVED_CLASSES=()
  for requested in "${EXPLICIT_CLASSES[@]}"; do
    case "$requested" in
      *.*)
        # Looks like an FQCN: Java requires the package to mirror the directory, so the source
        # file's location is derivable and its absence means the name is wrong.
        candidate="$TESTS/src/$(printf '%s' "$requested" | tr '.' '/').java"
        [ -f "$candidate" ] || die "no such test class: $requested (expected source at ${candidate#$REPO/})"
        RESOLVED_CLASSES+=("$requested")
        ;;
      *)
        # Bare class name: resolve to an FQCN instead of handing it to Class.forName as-is.
        matches=()
        while IFS= read -r hit; do
          [ -n "$hit" ] && matches+=("$hit")
        done < <(find "$TESTS/src" -name "$requested.java" \
                   | sed -e "s#^$TESTS/src/##" -e 's#/#.#g' -e 's#\.java$##' | sort)
        case "${#matches[@]}" in
          0) die "no such test class: $requested (searched ${TESTS#$REPO/}/src for $requested.java)" ;;
          1) echo "-- resolved '$requested' -> ${matches[0]}"; RESOLVED_CLASSES+=("${matches[0]}") ;;
          *) die "ambiguous class name '$requested' matches ${#matches[@]} classes: ${matches[*]} — pass the fully-qualified name" ;;
        esac
        ;;
    esac
  done
  EXPLICIT_CLASSES=("${RESOLVED_CLASSES[@]}")
fi

# JUnit + Hamcrest from the Eclipse install (version-robust glob).
JUNIT="$(ls "$ECLIPSE_PLUGINS"/org.junit_4*.jar 2>/dev/null | head -1)"
HAM="$(ls "$ECLIPSE_PLUGINS"/org.hamcrest_*.jar 2>/dev/null | head -1)"
[ -n "$JUNIT" ] || die "JUnit 4 jar not found under $ECLIPSE_PLUGINS (org.junit_4*.jar)"
[ -n "$HAM" ]   || die "Hamcrest jar not found under $ECLIPSE_PLUGINS (org.hamcrest_*.jar)"

# Vendored libs (both projects) + all Archi plugin jars + Mockito stack.
LIBS="$(find "$PROD/lib" "$TESTS/lib" -name '*.jar' 2>/dev/null | tr '\n' ':')"
ARCHIJARS="$(find "$ARCHI_PLUGINS" -name '*.jar' 2>/dev/null | tr '\n' ':')"
MOCKITO_CORE="$M2_REPO/org/mockito/mockito-core/5.5.0/mockito-core-5.5.0.jar"
MOCK="$MOCKITO_CORE:$M2_REPO/net/bytebuddy/byte-buddy/1.14.6/byte-buddy-1.14.6.jar:$M2_REPO/net/bytebuddy/byte-buddy-agent/1.14.6/byte-buddy-agent-1.14.6.jar:$M2_REPO/org/objenesis/objenesis/3.3/objenesis-3.3.jar"
# Validate the Mockito stack up front (it is version-pinned, not globbed) — otherwise a missing/
# mismatched version silently drops Mockito from the classpath and every @Mock test fails late
# with ClassNotFoundException.
[ -f "$MOCKITO_CORE" ] || die "Mockito not found: $MOCKITO_CORE (populate ~/.m2 via a Maven build, or set M2_REPO; tests using @Mock need it)"

OUT_PROD="$BUILD_DIR/prod"
OUT_TESTS="$BUILD_DIR/tests"
COMPILE_CP="$JUNIT:$HAM:$LIBS:$MOCK:$ARCHIJARS"
JAVAC_FLAGS=(--release 21 -encoding UTF-8 -nowarn)

# Report WHICH Archi is on the compile classpath, not just where it is. Two builds can sit at
# the same advertised version and expose different APIs -- a pre-release 5.10.0 returns EClass[]
# from ArchimateModelUtils.getValidRelationships where the 5.10.0 release returns List<EClass> --
# and when they disagree the symptom is a compile error in a file nobody edited. The bundle
# qualifier is a date, so a stale install is obvious at a glance rather than after a bisect.
ARCHI_MODEL_BUNDLE="$(basename "$(ls -d "$ARCHI_PLUGINS"/com.archimatetool.model_* 2>/dev/null | head -1)" 2>/dev/null)"
ARCHI_BUILD="${ARCHI_MODEL_BUNDLE#com.archimatetool.model_}"

echo "== headless build + test harness =="
echo "   repo:        $REPO"
echo "   ARCHI_HOME:  $ARCHI_HOME"
echo "   Archi build: ${ARCHI_BUILD:-UNKNOWN (no com.archimatetool.model bundle under $ARCHI_PLUGINS)}"
echo "   build dir:   $BUILD_DIR"
echo "   results dir: $RESULTS_DIR"

# ---- compile production source FROM SOURCE (never touches the Eclipse bin/) ----
echo "-- compiling production source ($(find "$PROD/src" -name '*.java' | wc -l | tr -d ' ') files) ..."
rm -rf "$OUT_PROD"; mkdir -p "$OUT_PROD"
find "$PROD/src" -name '*.java' > "$BUILD_DIR/prod-srcs.txt"
javac "${JAVAC_FLAGS[@]}" -cp "$COMPILE_CP" -d "$OUT_PROD" "@$BUILD_DIR/prod-srcs.txt" \
  || die "production compile failed (see javac output above)"
# Mirror Eclipse's bin.includes so classpath resource loads resolve (resources/, img/).
[ -d "$PROD/resources" ] && cp -R "$PROD/resources" "$OUT_PROD/"
[ -d "$PROD/img" ]       && cp -R "$PROD/img" "$OUT_PROD/"
# Package prod output (classes + resources) as a jar for the RUN classpath, so resource lookups
# behave exactly like the shipped OSGi bundle — e.g. getResourceAsStream on a DIRECTORY returns
# null, not an exploded-dir file listing (ResourceHandler.loadResourceFile("") must be null). The
# COMPILE classpaths can stay exploded; only the runtime needs jar semantics.
PROD_JAR="$BUILD_DIR/prod.jar"
( cd "$OUT_PROD" && jar cf "$PROD_JAR" . ) || die "failed to package prod.jar"
# Strip directory entries so getResourceAsStream on a directory path returns null (as in a real
# deployed bundle), not a 0-byte/dir-listing stream. `jar` always writes dir entries; zip -d drops
# them. File entries (the resources themselves) are unaffected.
if command -v zip >/dev/null; then
  zip -d "$PROD_JAR" '*/' >/dev/null 2>&1 || true
else
  # Not silent: without zip the jar keeps directory entries, so getResourceAsStream on a directory
  # path returns a 0-byte stream instead of null — a class like ResourceHandlerTest will then go
  # RED (loud), not silently wrong. m0-2 CI images must install zip (apt-get install zip).
  echo "WARN: 'zip' not found — cannot strip jar directory entries; directory-path resource lookups" >&2
  echo "      may diverge from a deployed bundle. Install zip (m0-2: apt-get install zip)." >&2
fi

# ---- compile test source FROM SOURCE (against fresh prod output) ----
echo "-- compiling test source ($(find "$TESTS/src" -name '*.java' | wc -l | tr -d ' ') files) ..."
rm -rf "$OUT_TESTS"; mkdir -p "$OUT_TESTS"
find "$TESTS/src" -name '*.java' > "$BUILD_DIR/test-srcs.txt"
javac "${JAVAC_FLAGS[@]}" -cp "$OUT_PROD:$COMPILE_CP" -d "$OUT_TESTS" "@$BUILD_DIR/test-srcs.txt" \
  || die "test compile failed (see javac output above)"
[ -d "$TESTS/testdata" ] && cp -R "$TESTS/testdata" "$OUT_TESTS/" 2>/dev/null || true

RUN_CP="$OUT_TESTS:$PROD_JAR:$COMPILE_CP"

# ---- build the class list: explicit args win; else scan ∖ excluded manifest ----
if [ "${#EXPLICIT_CLASSES[@]}" -gt 0 ]; then
  CLASSES=("${EXPLICIT_CLASSES[@]}")   # already resolved + validated during arg parsing
  echo "-- running ${#CLASSES[@]} explicitly-requested class(es)"
else
  # Scan every *Test.java -> FQCN (the 6 non-test helpers don't match *Test.java, so they're
  # excluded automatically). Then subtract the excluded manifest (data, not code).
  EXCLUDE_PATTERN="$(sed -e 's/#.*//' -e 's/[[:space:]]//g' "$EXCLUDED_MANIFEST" 2>/dev/null | grep . || true)"
  CLASSES=()  # while-read append, not mapfile — portable to bash 3.2 (macOS default)
  while IFS= read -r line; do
    [ -n "$line" ] && CLASSES+=("$line")
  done < <(
    find "$TESTS/src" -name '*Test.java' \
      | sed -e "s#^$TESTS/src/##" -e 's#/#.#g' -e 's#\.java$##' \
      | { if [ -n "$EXCLUDE_PATTERN" ]; then grep -vxF "$EXCLUDE_PATTERN"; else cat; fi; } \
      | sort
  )
  scanned="$(find "$TESTS/src" -name '*Test.java' | wc -l | tr -d ' ')"
  echo "-- scanned $scanned *Test.java; excluded $((scanned - ${#CLASSES[@]})) per manifest; running ${#CLASSES[@]}"
fi
[ "${#CLASSES[@]}" -gt 0 ] || die "no test classes to run"

# ---- SWT escape hatch: -XstartOnFirstThread on macOS only ----
JVM_FLAGS=()
if [ "$SWT" -eq 1 ]; then
  if [ "$(uname -s)" = "Darwin" ]; then
    JVM_FLAGS+=(-XstartOnFirstThread)
    echo "-- --swt: added -XstartOnFirstThread (macOS)"
  else
    echo "-- --swt: no-op on $(uname -s) (use xvfb for a headless display)"
  fi
fi

mkdir -p "$RESULTS_DIR"
# Clear prior JUnit XML so the results dir describes THIS invocation and nothing else.
# Without this the dir accumulates: a full run leaves behind files for classes it did not run
# (a class excluded from both lanes keeps whatever a per-class run wrote earlier), so a later
# "parse every XML and confirm 0 failures" census silently reads stale results as current
# coverage. Deliberately narrow — only the harness's own TEST-*.xml, never the directory —
# and it runs ONCE here, before pass 1, so --release pass 2 still accumulates into the same dir.
stale_xml="$(find "$RESULTS_DIR" -maxdepth 1 -name 'TEST-*.xml' | wc -l | tr -d ' ')"
if [ "$stale_xml" -gt 0 ]; then
  find "$RESULTS_DIR" -maxdepth 1 -name 'TEST-*.xml' -delete
  echo "-- cleared $stale_xml JUnit XML file(s) from a previous run ($RESULTS_DIR)"
fi
echo "-- running tests (JUnit XML -> $RESULTS_DIR) ..."
LOG="$BUILD_DIR/run.log"
# ${arr[@]+"${arr[@]}"} = expand to nothing when empty (bash 3.2 + `set -u` would otherwise abort).
java ${JVM_FLAGS[@]+"${JVM_FLAGS[@]}"} \
  -Dharness.xmlDir="$RESULTS_DIR" \
  -Dharness.knownFailingFile="$KNOWN_FAILING" \
  -cp "$RUN_CP" "$RUNNER_CLASS" "${CLASSES[@]}" > "$LOG" 2>&1
rc=$?

# Preserve the run-pins.sh SLF4J stdout-hygiene; show the per-class results + summary block.
grep -vE 'SLF4J' "$LOG"

# ---- pass 2 (--release only): the display-required "Group B" bucket -----------
# Runs in a SECOND JVM because -XstartOnFirstThread must be set at JVM start and must NOT be
# applied to the headless pass. Compilation is not repeated — both passes share the classes
# built above, so the gate costs one compile, not two.
#
# The class list is DERIVED from the manifest's "Group B" block with the SAME awk the ci-pde
# workflow lane uses, so the dev-box gate and CI cannot disagree about what the display bucket
# is. Adding a class to Group B extends both with no second list to maintain.
if [ "$RELEASE" -eq 1 ]; then
  GROUP_B=()
  while IFS= read -r line; do
    [ -n "$line" ] && GROUP_B+=("$line")
  done < <(
    awk '/^# --- Group B/{g=1; next} /^# --- Group/{g=0} g && $1 !~ /^#/ && NF {print $1}' \
      "$EXCLUDED_MANIFEST"
  )
  # A malformed manifest must not silently run zero display tests and report a green release.
  [ "${#GROUP_B[@]}" -gt 0 ] || die "--release: no Group B (display) classes parsed from $EXCLUDED_MANIFEST"

  echo
  echo "== pass 2/2: display bucket (${#GROUP_B[@]} classes from the manifest's Group B) =="
  DISPLAY_FLAGS=()
  if [ "$(uname -s)" = "Darwin" ]; then
    DISPLAY_FLAGS+=(-XstartOnFirstThread)
    echo "-- macOS: -XstartOnFirstThread (SWT binds to the Cocoa window server)"
  elif [ -z "${DISPLAY:-}" ]; then
    # Loud, not silent: on a display-less Linux box these classes throw SWTError
    # "No more handles [gtk_init_check() failed]". Say so BEFORE the run so the failure is
    # read as "no display" rather than as a code regression.
    echo "WARN: no DISPLAY set on $(uname -s) — the Group B classes need one." >&2
    echo "      Re-run under a virtual display, e.g.:" >&2
    echo "        xvfb-run --auto-servernum --server-args=\"-screen 0 1280x1024x24\" bash tools/run-tests.sh --release" >&2
  fi

  LOG2="$BUILD_DIR/run-display.log"
  java ${DISPLAY_FLAGS[@]+"${DISPLAY_FLAGS[@]}"} \
    -Dharness.xmlDir="$RESULTS_DIR" \
    -Dharness.knownFailingFile="$KNOWN_FAILING" \
    -cp "$RUN_CP" "$RUNNER_CLASS" "${GROUP_B[@]}" > "$LOG2" 2>&1
  rc2=$?
  grep -vE 'SLF4J' "$LOG2"

  echo
  if [ "$rc" -ne 0 ] || [ "$rc2" -ne 0 ]; then
    # Name WHICH pass failed — "the release gate is red" is not actionable on its own.
    [ "$rc"  -ne 0 ] && echo "RELEASE GATE: headless pass FAILED"
    [ "$rc2" -ne 0 ] && echo "RELEASE GATE: display pass FAILED"
    exit 1
  fi
  echo "RELEASE GATE PASS (headless + display)"
  exit 0
fi

exit "$rc"
