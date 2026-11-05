#!/usr/bin/env bash
#
# Build the installable Archi plug-in (.archiplugin) by compiling the bundle FROM SOURCE
# and assembling it into Archi's plug-in package format — no Eclipse IDE / PDE export needed.
#
# This mirrors the compile-from-source approach proven by tools/run-tests.sh (which the project
# found cleaner than the Eclipse PDE build), and packages the result into the exact .archiplugin
# layout Archi's "Manage plug-ins" installer expects:
#
#   <out>.archiplugin   (a zip)
#   ├── archi-plugin                              # 54-byte magic marker Archi looks for
#   └── <Bundle-SymbolicName>_<version.TIMESTAMP>/
#       ├── META-INF/MANIFEST.MF                  # .qualifier replaced by TIMESTAMP
#       ├── plugin.xml, plugin.properties         # bin.includes file entries
#       ├── META-INF/services/...                 # everything else under META-INF/, as is
#       ├── <compiled classes tree>              # Bundle-ClassPath starts with '.', so classes
#       │                                           live at the bundle root (exploded), not a jar
#       ├── resources/, img/                      # bin.includes
#       └── lib/...                               # vendored third-party jars (Bundle-ClassPath)
#
# The bundle holds exactly the entries of build.properties `bin.includes`, read from that file:
# a directory entry is copied whole, a file entry is copied, and '.' is the compiled classes plus
# any non-.java file under src/. macOS litter (.DS_Store, and __MACOSX as a whole path component)
# is left out. An entry that names nothing on disk, or a directory it cannot read in full, stops
# the build. After zipping, the archive is listed and checked against the same entries: a file
# that bin.includes ships but the archive lacks fails the build and the archive is deleted. That
# check compares the archive with the source tree, so it cannot see a file missing from both; a
# second check, which reads nothing from the source tree, requires the installer marker,
# MANIFEST.MF, at least one class, every Bundle-ClassPath jar, the Bundle-Activator class, the SLF4J services file, and the class of every provider a services
# file names. SLF4J finds the plugin's log bridge through META-INF/services, so a bundle without
# that file logs nothing at all — these checks are what keep that from shipping unseen.
# build.properties exclusion keys, or a source.. other than src/, stop the build: this packager
# does not implement them. So do symbolic links, other special files, and names holding a non-ASCII
# or control character or a backslash in the bundle, which it could neither copy nor check
# faithfully on every platform.
#
# Usage:
#   tools/ci/package-plugin.sh                    # -> build/dist/<sym>_<version>.archiplugin
#   ARCHI_HOME=/path/to/Archi/Eclipse tools/ci/package-plugin.sh
#
# Env vars (macOS dev-box defaults, same contract as run-tests.sh):
#   ARCHI_HOME   Archi's Eclipse dir   (default /Applications/Archi.app/Contents/Eclipse)
#   BUILD_DIR    compile output dir    (default <repo>/build/plugin-build)
#   DIST_DIR     output dir            (default <repo>/build/dist)
#                A relative BUILD_DIR or DIST_DIR is resolved against the directory the script
#                is run from.
#   QUALIFIER    build qualifier       (default: UTC yyyyMMddHHmm)
#
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$SCRIPT_DIR/../.." && pwd)"
PROD="$REPO/net.vheerden.archi.mcp"

ARCHI_HOME="${ARCHI_HOME:-/Applications/Archi.app/Contents/Eclipse}"
BUILD_DIR="${BUILD_DIR:-$REPO/build/plugin-build}"
DIST_DIR="${DIST_DIR:-$REPO/build/dist}"
# Resolve a relative path against the caller's directory now: the staging and zip steps cd and
# then use these paths. Nothing is created here.
case "$BUILD_DIR" in /*) ;; *) BUILD_DIR="$PWD/$BUILD_DIR" ;; esac
case "$DIST_DIR" in /*) ;; *) DIST_DIR="$PWD/$DIST_DIR" ;; esac
QUALIFIER="${QUALIFIER:-$(date -u +%Y%m%d%H%M)}"

die() { echo "ERROR: $*" >&2; exit 2; }

ARCHI_PLUGINS="$ARCHI_HOME/plugins"
MANIFEST="$PROD/META-INF/MANIFEST.MF"
[ -d "$ARCHI_PLUGINS" ] || die "ARCHI_HOME/plugins not found: $ARCHI_PLUGINS (set ARCHI_HOME)"
[ -d "$PROD/src" ]      || die "production source not found: $PROD/src"
[ -f "$MANIFEST" ]      || die "MANIFEST.MF not found: $MANIFEST"
command -v javac >/dev/null || die "javac not on PATH (need a JDK 21+)"
command -v zip   >/dev/null || die "zip not on PATH"
command -v unzip >/dev/null || die "unzip not on PATH (the archive is listed to verify it)"

# ---- read bundle identity from MANIFEST.MF (continuation-line safe) ----
manifest_value() {
  # prints the value of header $1, folding RFC822 continuation lines (leading space) and
  # stripping OSGi attributes after ';'
  awk -v h="$1:" '
    $0 ~ "^"h {val=$0; sub("^"h" *","",val); getline; while ($0 ~ /^ /){sub(/^ /,"");val=val$0; getline} print val}
  ' "$MANIFEST" | sed -E 's/;.*//' | tr -d '\r' | head -1
}
SYM="$(manifest_value 'Bundle-SymbolicName')"
RAW_VERSION="$(manifest_value 'Bundle-Version')"
[ -n "$SYM" ]         || die "could not read Bundle-SymbolicName from MANIFEST"
[ -n "$RAW_VERSION" ] || die "could not read Bundle-Version from MANIFEST"
# Replace a trailing .qualifier with the build timestamp (what Eclipse export does).
VERSION="${RAW_VERSION/.qualifier/.$QUALIFIER}"
BUNDLE_ID="${SYM}_${VERSION}"

# ---- bin.includes from build.properties: one entry per line ----
BUILD_PROPERTIES="$PROD/build.properties"
[ -f "$BUILD_PROPERTIES" ] || die "build.properties not found: $BUILD_PROPERTIES"
# This packager models one source folder (src/) and no exclusions. Refuse anything else rather
# than ship a bundle that differs from what an Eclipse export of the same build.properties would.
if grep -Eq '^[[:space:]]*(bin\.excludes|src\.excludes|exclude\.\.)[[:space:]]*[=:]' "$BUILD_PROPERTIES"; then
  die "build.properties uses an exclusion key; tools/ci/package-plugin.sh does not implement exclusions"
fi
SOURCE_DOT="$(awk '/^[ \t]*source\.\.[ \t]*[=:]/ { sub(/^[^=:]*[=:][ \t]*/, ""); sub(/[ \t\r]*$/, ""); v=$0 } END { print v }' "$BUILD_PROPERTIES")"
[ "$SOURCE_DOT" = "src/" ] || die "build.properties source.. is '$SOURCE_DOT'; this packager compiles src/ only"
bin_includes() {
  # folds '\'-continued lines of the bin.includes key, then splits on ',' and trims.
  # A repeated key replaces the earlier value, as java.util.Properties does. A leading './'
  # (repeated or not) is dropped when a name follows it, so './plugin.xml' and 'plugin.xml' name the
  # same file everywhere below. Anything else that starts with './' or '/' ('./', './/', './/x') is
  # left as written and refused, rather than becoming empty or absolute.
  awk '
    /^[ \t]*bin\.includes[ \t]*[=:]/ { f=1; buf=""; sub(/^[^=:]*[=:]/, "") }
    f { line=$0; more=(line ~ /\\[ \t\r]*$/); sub(/\\[ \t\r]*$/, "", line); buf=buf line; if (!more) f=0 }
    END { print buf }
  ' "$BUILD_PROPERTIES" | tr ',' '\n' | tr -d '\r' | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//' \
    | sed -E 's#^(\./)+([^/])#\2#' | grep -v '^$'
}

# Files a bin.includes entry contributes, as bundle-relative paths. '.' is the output of
# source.. : the compiled classes plus every non-.java file under src/ (what JDT copies to bin/).
# macOS litter (.DS_Store, and __MACOSX as a whole path component) is never part of the bundle.
entry_files() {
  case "$1" in
    .)  ( cd "$OUT_CLASSES" && find . -type f ) | sed 's#^\./##'
        ( cd "$PROD/src" && find . -type f ! -name '*.java' ! -name .DS_Store ! -path '*/__MACOSX/*' ! -name __MACOSX ) | sed 's#^\./##' ;;
    */) ( cd "$PROD" && find "./${1%/}" -type f ! -name .DS_Store ! -path '*/__MACOSX/*' ! -name __MACOSX ) | sed 's#^\./##' ;;
    *)  printf '%s\n' "$1" ;;
  esac
}

# Paths a bin.includes entry reaches that are not regular files or directories, relative to the
# bundle source and each followed by its kind: a symbolic link anywhere on the entry's own path
# (the entry or a directory above it; src/ for '.'), and, under a directory entry or src/, every
# symbolic link and every other non-regular file (fifo, socket, device).
entry_irregular_files() {
  local path rest head walked=""
  case "$1" in .) path="src" ;; *) path="${1%/}" ;; esac
  rest="$path"
  while [ -n "$rest" ]; do
    head="${rest%%/*}"
    walked="${walked:+$walked/}$head"
    [ -L "$PROD/$walked" ] && printf '%s (symbolic link)\n' "$walked"
    case "$rest" in */*) rest="${rest#*/}" ;; *) rest="" ;; esac
  done
  case "$1" in
    .|*/) ( cd "$PROD" && find "./$path" -type l ! -name .DS_Store ! -path '*/__MACOSX/*' ! -name __MACOSX ) \
            | sed -e 's#^\./##' -e 's/$/ (symbolic link)/'
          ( cd "$PROD" && find "./$path" ! -type f ! -type d ! -type l ! -name .DS_Store ! -path '*/__MACOSX/*' ! -name __MACOSX ) \
            | sed -e 's#^\./##' -e 's/$/ (not a regular file)/' ;;
  esac
}

# Names a bin.includes entry reaches that match the find -name pattern $2, each followed by $3: the
# entry's own path, and every file and directory under a directory entry, or, for '.', under src/
# (its .java files aside) and the compiled classes. A newline in a name is shown as '?', so each
# name stays on one line.
entry_names_matching() {
  case "$1" in
    .)  ( cd "$PROD" && find ./src ! \( -type f -name '*.java' \) -name "$2" ! -name .DS_Store ! -path '*/__MACOSX/*' ! -name __MACOSX -print0 )
        ( cd "$OUT_CLASSES" && find . -name "$2" -print0 ) ;;
    */) ( cd "$PROD" && find "./${1%/}" -name "$2" ! -name .DS_Store ! -path '*/__MACOSX/*' ! -name __MACOSX -print0 )
        case "${1%/}" in $2) printf '%s\000' "${1%/}" ;; esac ;;
    *)  case "$1" in $2) printf '%s\000' "$1" ;; esac ;;
  esac | tr '\n\000' '?\n' | sed -e 's#^\./##' -e "s/\$/ $3/"
}

# Stops the build, listing every bundle file the packager cannot ship and verify faithfully:
# - a symbolic link or other non-regular file, because `find -type f` skips it when the bundle is
#   copied and again when the archive is checked, so it would be left out with nothing reporting it;
# - a name holding a byte outside printable ASCII (a control character such as a newline, or any
#   non-ASCII byte), because the file lists below are one name per line, and unzip lists such a name
#   escaped (or sorted differently) depending on the locale, so the archive check would misread it;
# - a name holding a backslash, because GNU tar reads it as an escape in a file list, and Windows
#   reads it as a directory separator.
# Everything here runs under LC_ALL=C: in a UTF-8 locale, sed fails on a byte that is not valid UTF-8,
# which would empty the list and let the file through, and the find pattern for "outside printable
# ASCII" matches every name.
refuse_unshippable_files() {
  local bad
  bad="$( export LC_ALL=C; {
    printf '%s\n' "$ENTRIES" | while IFS= read -r e; do entry_irregular_files "$e"; done
    printf '%s\n' "$ENTRIES" | while IFS= read -r e; do entry_names_matching "$e" '*[! -~]*' '(non-ASCII or control character in name)'; done
    printf '%s\n' "$ENTRIES" | while IFS= read -r e; do entry_names_matching "$e" '*\\*' '(backslash in name)'; done
  } | sort -u )"
  [ -z "$bad" ] && return 0
  echo "ERROR: the bundle holds files this packager does not ship:" >&2
  printf '%s\n' "$bad" | LC_ALL=C sed 's/^/  /' >&2
  die "symbolic links, special files, and names with non-ASCII or control characters or a backslash are not supported in the bundle"
}

# Copies the files listed in $BUILD_DIR/stage-list.txt (paths relative to $1) into the bundle dir,
# keeping their relative paths. An empty list copies nothing. Every name reaches tar as './…', so a
# name starting with '-' is never read as an option; a blank line is dropped rather than becoming './'.
stage_files() {
  [ -s "$BUILD_DIR/stage-list.txt" ] || return 0
  sed -e '/^$/d' -e '/^\.\//!s#^#./#' "$BUILD_DIR/stage-list.txt" > "$BUILD_DIR/stage-list.dot.txt"
  ( cd "$1" && COPYFILE_DISABLE=1 tar cf - -T "$BUILD_DIR/stage-list.dot.txt" ) | ( cd "$BUNDLE_DIR" && tar xf - )
}

# SLF4J finds the plugin's log bridge only through this file; without it every log line of the
# bundle is discarded and nothing says so. It is required by name because the bin.includes check
# below compares the archive with the source tree, and so cannot notice a file missing from both.
REQUIRED_FILES="META-INF/MANIFEST.MF
META-INF/services/org.slf4j.spi.SLF4JServiceProvider"

# Fails unless every file of every bin.includes entry is in the archive under the bundle dir,
# the archive holds what any installable bundle needs (see verify_bundle_floor), and it holds no
# macOS litter.
verify_archive() {
  local expected actual missing
  expected="$BUILD_DIR/expected-entries.txt"; actual="$BUILD_DIR/archive-entries.txt"
  bin_includes | while IFS= read -r e; do entry_files "$e"; done | sed "s#^#$BUNDLE_ID/#" | sort -u > "$expected"
  unzip -Z1 "$1" > "$actual.raw" || { rm -f "$1"; die "could not list $1"; }
  grep -v '/$' "$actual.raw" | sort -u > "$actual"
  missing="$(comm -23 "$expected" "$actual")"
  if [ -n "$missing" ]; then
    echo "ERROR: archive is missing files that build.properties bin.includes ships:" >&2
    echo "$missing" | sed 's/^/  /' >&2
    rm -f "$1"; exit 1
  fi
  missing="$(verify_bundle_floor "$1" "$actual")"
  if [ -n "$missing" ]; then
    echo "ERROR: archive is not an installable bundle:" >&2
    echo "$missing" | sed 's/^/  /' >&2
    rm -f "$1"; exit 1
  fi
  if grep -E '(^|/)(\.DS_Store|__MACOSX)(/|$)' "$actual" >&2; then
    echo "ERROR: archive holds macOS litter (listed above)" >&2
    rm -f "$1"; exit 1
  fi
  echo "-- verified: all $(wc -l < "$expected" | tr -d ' ') bin.includes files are in the archive"
}

# Prints one line per thing an installable bundle needs that the archive (listing $2) lacks:
# the installer marker, REQUIRED_FILES, at least one class, every Bundle-ClassPath jar, the
# Bundle-Activator class, and the class of every provider a META-INF/services file names.
# None of this is derived from the source tree, so it holds even when a file is missing there.
verify_bundle_floor() {
  local zip="$1" list="$2" f cp act svc cls
  grep -qx 'archi-plugin' "$list" || echo "archi-plugin (installer marker)"
  echo "$REQUIRED_FILES" | while IFS= read -r f; do
    grep -qxF "$BUNDLE_ID/$f" "$list" || echo "$f"
  done
  grep -q "^$BUNDLE_ID/.*\.class\$" "$list" || echo "any .class file (is '.' missing from bin.includes?)"
  manifest_value 'Bundle-ClassPath' | tr ',' '\n' | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//' \
    | while IFS= read -r cp; do
        case "$cp" in ''|.) ;; *) grep -qxF "$BUNDLE_ID/$cp" "$list" || echo "$cp (Bundle-ClassPath)" ;; esac
      done
  act="$(manifest_value 'Bundle-Activator')"
  if [ -n "$act" ]; then
    grep -qxF "$BUNDLE_ID/$(echo "$act" | tr '.' '/').class" "$list" || echo "$act (Bundle-Activator)"
  fi
  grep "^$BUNDLE_ID/META-INF/services/." "$list" | while IFS= read -r svc; do
    unzip -p "$zip" "$svc" | tr -d '\r' | sed -e 's/#.*//' -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//' | grep -v '^$' \
      > "$BUILD_DIR/providers.txt"
    [ -s "$BUILD_DIR/providers.txt" ] || echo "${svc#$BUNDLE_ID/} (names no provider)"
    while IFS= read -r cls; do
      grep -qxF "$BUNDLE_ID/$(echo "$cls" | tr '.' '/').class" "$list" || echo "$cls (provider in ${svc#$BUNDLE_ID/})"
    done < "$BUILD_DIR/providers.txt"
  done
}

echo "== Archi plug-in packager =="
echo "   bundle:      $SYM"
echo "   version:     $RAW_VERSION -> $VERSION"
echo "   ARCHI_HOME:  $ARCHI_HOME"

# An archive of the same name from an earlier run is removed first, so a build that stops at any
# check below never leaves a stale archive where this one would have been.
ARCHIPLUGIN="$DIST_DIR/${BUNDLE_ID}.archiplugin"
rm -f "$ARCHIPLUGIN"

# ---- compile production source FROM SOURCE against the Archi/lib classpath ----
OUT_CLASSES="$BUILD_DIR/classes"
LIBS="$(find "$PROD/lib" -name '*.jar' 2>/dev/null | tr '\n' ':')"
ARCHIJARS="$(find "$ARCHI_PLUGINS" -name '*.jar' 2>/dev/null | tr '\n' ':')"
COMPILE_CP="$LIBS$ARCHIJARS"
rm -rf "$OUT_CLASSES"; mkdir -p "$OUT_CLASSES"
echo "-- compiling ($(find "$PROD/src" -name '*.java' | wc -l | tr -d ' ') files) ..."
find "$PROD/src" -name '*.java' > "$BUILD_DIR/srcs.txt"
javac --release 21 -encoding UTF-8 -nowarn -cp "$COMPILE_CP" -d "$OUT_CLASSES" "@$BUILD_DIR/srcs.txt" \
  || die "production compile failed (see javac output above)"

# ---- check bin.includes before anything is staged ----
ENTRIES="$(bin_includes)"
[ -n "$ENTRIES" ] || die "no bin.includes entries in $BUILD_PROPERTIES"
# Read line by line: an entry is never word-split or glob-expanded. Every directory an entry reaches
# is read once with find here: a directory find cannot read would be left out of the bundle and out
# of the archive check alike, so a find error stops the build (find has named the directory above).
while IFS= read -r e; do
  case "$e" in
    .)  ( cd "$PROD" && find ./src > /dev/null ) && ( cd "$OUT_CLASSES" && find . > /dev/null ) \
          || die "could not read everything under bin.includes entry '.' (see find's message above)" ;;
    ./) die "a bin.includes entry made only of './' names the whole project directory; list what the bundle holds instead" ;;
    ./*|/*) die "bin.includes entry '$e' is not a plain path inside $PROD" ;;
    */) [ -d "$PROD/${e%/}" ] || die "bin.includes entry '$e' is not a directory under $PROD"
        ( cd "$PROD" && find "./${e%/}" > /dev/null ) \
          || die "could not read everything under bin.includes entry '$e' (see find's message above)" ;;
    *)  [ -f "$PROD/$e" ]     || die "bin.includes entry '$e' is not a file under $PROD" ;;
  esac
done <<EOF
$ENTRIES
EOF
refuse_unshippable_files

# ---- assemble the bundle folder from bin.includes ----
STAGE="$BUILD_DIR/stage"
BUNDLE_DIR="$STAGE/$BUNDLE_ID"
rm -rf "$STAGE"; mkdir -p "$BUNDLE_DIR"
while IFS= read -r e; do
  # Bundle-ClassPath starts with '.', so the classes and src/ resources go at the bundle root (exploded).
  if [ "$e" = "." ]; then
    ( cd "$OUT_CLASSES" && find . -type f ) > "$BUILD_DIR/stage-list.txt"
    stage_files "$OUT_CLASSES" || die "failed to stage compiled classes"
    ( cd "$PROD/src" && find . -type f ! -name '*.java' ! -name .DS_Store ! -path '*/__MACOSX/*' ! -name __MACOSX ) > "$BUILD_DIR/stage-list.txt"
    stage_files "$PROD/src" || die "failed to stage src/ resources"
  else
    entry_files "$e" > "$BUILD_DIR/stage-list.txt"
    stage_files "$PROD" || die "failed to stage bin.includes entry '$e'"
  fi
done <<EOF
$ENTRIES
EOF
# MANIFEST with .qualifier resolved to the timestamp (mirrors Eclipse export).
mkdir -p "$BUNDLE_DIR/META-INF"
sed "s/^Bundle-Version: .*/Bundle-Version: $VERSION/" "$MANIFEST" > "$BUNDLE_DIR/META-INF/MANIFEST.MF"

# ---- the magic marker (verified byte-identical to shipped Archi plug-ins) ----
printf 'Magic file to signify this is an Archi plug-in bundle.' > "$STAGE/archi-plugin"

# ---- zip it up as <sym>_<version>.archiplugin (marker + bundle folder at the zip root) ----
mkdir -p "$DIST_DIR"
( cd "$STAGE" && zip -qr "$ARCHIPLUGIN" archi-plugin "$BUNDLE_ID" ) \
  || { rm -f "$ARCHIPLUGIN"; die "failed to zip .archiplugin"; }
verify_archive "$ARCHIPLUGIN"

echo "-- wrote $ARCHIPLUGIN ($(du -h "$ARCHIPLUGIN" | cut -f1))"
echo "RESULT: OK"
