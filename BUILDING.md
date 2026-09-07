# Building Archi MCP Server from source

This guide covers how to set up a build environment, run the test suite, and build the
installable plug-in from source.

For **what the project does** and **how to install/use the released plug-in**, see the
[README](README.md). For **architecture** (layer boundaries, project structure, key decisions),
see the README's *Developer Guide* section.

---

## TL;DR

```bash
# 1. Run the test suite headlessly (needs a local Archi install — see Prerequisites)
export ARCHI_HOME=/Applications/Archi.app/Contents/Eclipse   # adjust for your OS
# ...provision JUnit/Hamcrest + Mockito (see "Running the tests") then:
tools/run-tests.sh

# 2. Build an installable plug-in
ARCHI_HOME="$ARCHI_HOME" tools/ci/package-plugin.sh
# -> build/dist/net.vheerden.archi.mcp_<version>.archiplugin
```

CI runs the test suite on every push; a failing test turns the build red.

---

## Prerequisites

| Need | Why | Notes |
|------|-----|-------|
| **JDK 21+** | Archi 5.x targets Java 21 | `java -version` / `javac -version` must both be 21+ |
| **A local Archi install** | provides the Archi/EMF/GEF/SWT jars the build compiles against | Download from <https://www.archimatetool.com/download/>. `ARCHI_HOME` is the directory that contains `plugins/` (macOS: `/Applications/Archi.app/Contents/Eclipse`; Linux: `<extracted>/Archi`) |
| **Maven** | fetches the pinned test dependencies | only needed to provision JUnit/Hamcrest/Mockito (below) |
| **`zip`, `bash`, `git`** | packaging + scripts | `zip` is required so resource lookups match a deployed bundle |

This is an **Eclipse PDE / OSGi plug-in**, but you do **not** need the Eclipse IDE to build or
test — the scripts compile from source with `javac` against the Archi jar set.

---

## Running the tests

The test harness `tools/run-tests.sh` compiles both projects from source and runs the
headless-safe test classes, emitting JUnit XML. It reads three dependency roots via env vars:

| Env var | Points at | Default (macOS) |
|---------|-----------|-----------------|
| `ARCHI_HOME` | Archi's Eclipse dir (has `plugins/`) | `/Applications/Archi.app/Contents/Eclipse` |
| `ECLIPSE_HOME` | a dir whose `plugins/` holds `org.junit_4*.jar` + `org.hamcrest_*.jar` | `/Applications/Eclipse.app/Contents/Eclipse` |
| `M2_REPO` | a Maven local repo with the pinned Mockito stack | `~/.m2/repository` |

If you already run a full Eclipse IDE, `ECLIPSE_HOME` can point at it. Otherwise, provision the
test dependencies from Maven Central exactly as CI does:

```bash
# Populate ~/.m2 with the pinned Mockito stack + JUnit/Hamcrest
mvn -q -f tools/ci/pom.xml dependency:go-offline

# Provide JUnit/Hamcrest under the Eclipse-qualified names the harness expects
mkdir -p /tmp/eclipse-junit/plugins
cp ~/.m2/repository/junit/junit/4.13.2/junit-4.13.2.jar        /tmp/eclipse-junit/plugins/org.junit_4.13.2.jar
cp ~/.m2/repository/org/hamcrest/hamcrest/3.0/hamcrest-3.0.jar /tmp/eclipse-junit/plugins/org.hamcrest_3.0.0.jar

export ARCHI_HOME=/Applications/Archi.app/Contents/Eclipse   # adjust for your OS
export ECLIPSE_HOME=/tmp/eclipse-junit
export M2_REPO="$HOME/.m2/repository"

tools/run-tests.sh
```

A green run prints, e.g.:

```
SUMMARY: 252 classes | 5389 tests run | 0 failed | 0 errors | 0 empty-class violations
RESULT: PASS
```

The class and test counts grow with the suite — treat the figures above as the shape of a green run, not a target to match. What matters is `0 failed | 0 errors | 0 empty-class violations`.

Useful invocations:

```bash
tools/run-tests.sh ClassA ClassB     # run only specific fully-qualified test classes
tools/run-tests.sh --swt [Class…]    # add -XstartOnFirstThread for SWT classes (macOS)
```

JUnit XML is written to `build/test-results/`. The harness **exit code is the gate**: non-zero
on a compile error, any non-quarantined failure, or a class that contributes zero tests.

### The two test manifests

- `tools/osgi-excluded-tests.txt` — classes that cannot run in a plain headless JVM (they need a
  live OSGi/Archi runtime or an SWT display). The default run skips these. To run them you need an
  Eclipse PDE test environment (and a virtual display such as `xvfb` for the SWT ones).
- `tools/known-failing-tests.txt` — quarantined, pre-existing failures. They are still compiled,
  run, and reported, but they do **not** turn the build red. This list should shrink to empty: if
  you fix one, delete its line so it rejoins the gating set.

If you add a test that genuinely can't run headlessly, add it to the excluded manifest with a
one-line reason. Don't quarantine a test just to make CI green — quarantine is for tracked,
pre-existing defects only.

---

## Building the installable plug-in

`tools/ci/package-plugin.sh` compiles the bundle from source and packages it in Archi's
`.archiplugin` format (no Eclipse export needed):

```bash
ARCHI_HOME=/Applications/Archi.app/Contents/Eclipse tools/ci/package-plugin.sh
# -> build/dist/net.vheerden.archi.mcp_<version>.<timestamp>.archiplugin
```

To install it into Archi: **Help → Manage plug-ins… → Install…**, select the `.archiplugin`,
then restart Archi.

> The version qualifier is stamped from the build time. For a reproducible name on a release,
> pass `QUALIFIER=<value>` explicitly.

---

## Continuous integration

`.github/workflows/ci.yml` runs on every push to `main`, on pull requests, and on demand
(*Run workflow*). It provisions Archi + the pinned test dependencies on a Linux runner and runs the
suite in **two lanes**, each invoking `tools/run-tests.sh` unmodified; both upload JUnit XML and a
job summary. A failing test in either lane turns the build red.

- **`ci-junit`** — the headless pure-JUnit bucket (the bulk of the suite, currently ~250 classes /
  ~5,400 tests). No display is needed, so it runs without `xvfb`, and it auto-discovers `*Test.java` — a
  new headless test is picked up with no workflow change.
- **`ci-pde`** — the SWT-display classes listed in `tools/osgi-excluded-tests.txt` (a small subset),
  run under **`xvfb`** so SWT can bind to a virtual framebuffer. The harness runs in explicit-FQCN
  mode for just those classes; no JVM display flag is passed (the display comes from `xvfb`). It
  reuses `ci-junit`'s Archi and Maven caches.

The remaining OSGi/PDE-only classes — those that need a live Eclipse platform, not merely a display
— are **not** yet run in CI; exercise them locally via the Eclipse PDE JUnit runner
(`AllPluginTestsRunner`).

**Archi version is pinned** (`ARCHI_VERSION` in the workflow) so builds are reproducible — CI does
not auto-fetch "latest Archi". To try a different Archi version without editing files, run the
workflow manually and set the `archi_version` input; to adopt it, change that one line.

---

## Code layout & conventions

If you're building from source or patching locally:

- **Java 21**, 4-space indentation, match the style of the surrounding code.
- **Respect the architecture layer boundaries** (Protocol → Handlers → Model → UI) described in
  the README's *Developer Guide → Architecture Layers*. In particular, only the `model/` package
  touches the EMF/Archi model APIs; handlers work with DTOs through the accessor.
- **Keep the tests green.** New behavior should have tests under `net.vheerden.archi.mcp.tests`,
  following the `<ClassUnderTest>Test` / `shouldDoX_whenY()` naming pattern. Run
  `tools/run-tests.sh` before relying on a local build.
- Use SLF4J for logging (no `System.out.println`).

To **add a tool, handler, layout algorithm, or MCP resource**, follow the step-by-step patterns in
the [Extension Guide](docs/extension-guide.md).

---

## License

This project is released under the [MIT License](LICENSE).
