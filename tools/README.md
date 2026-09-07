# tools/ — headless build + test harness

## One command

```bash
tools/run-tests.sh
```

On a machine with a local Archi 5.7 install this:

1. **compiles both projects from source** with `javac --release 21` against the assembled Archi +
   Eclipse + vendored-`lib/` + Mockito classpath (no dependency on the Eclipse-produced `bin/`);
2. **auto-discovers the test class list** by scanning `net.vheerden.archi.mcp.tests/src` for every
   `*Test.java` and subtracting the exclusion manifest (below) — there is no hand-maintained list;
3. **runs the headless-safe classes** via a JUnit4 runner that asserts **`testsRun > 0` per class**
   (fails loudly if a class contributes zero executed tests) and **emits surefire JUnit XML**;
4. **exits non-zero** if any non-quarantined test fails, errors, or runs zero tests.

~95% of the suite runs headlessly in well under a minute. A human-readable per-class
`[ok]/[FAIL]/[KNOWN]` list plus a `SUMMARY` / `RESULT` line print to stdout; JUnit XML lands in
`build/test-results/` for CI to publish.

**The results dir describes one invocation.** Prior `TEST-*.xml` is cleared at the start of every
run, so a "parse every XML and confirm 0 failures" census cannot read a leftover file from an
earlier run as current coverage. Both `--release` passes accumulate into the same dir, because the
clear happens once before pass 1. Copy the XML elsewhere if you need to compare two runs.

### Variants

```bash
tools/run-tests.sh ClassA ClassB        # compile, then run ONLY these classes
tools/run-tests.sh --swt <Class>        # add -XstartOnFirstThread (macOS) for SWT/display classes
```

Named classes may be fully qualified (`net.vheerden.archi.mcp.server.CertificateGeneratorTest`) or
a bare class name (`CertificateGeneratorTest`), which is resolved against the test source tree and
echoed as `-- resolved '<name>' -> <fqcn>`. A name that matches nothing, or that is ambiguous
across packages, is rejected **before** the compile with a message naming the argument.

## Environment variables (macOS dev-box defaults)

| Var            | Default                                          | Purpose                          |
|----------------|--------------------------------------------------|----------------------------------|
| `ARCHI_HOME`   | `/Applications/Archi.app/Contents/Eclipse`       | Archi plugin jars                |
| `ECLIPSE_HOME` | `/Applications/Eclipse.app/Contents/Eclipse`     | JUnit 4 + Hamcrest jars          |
| `M2_REPO`      | `~/.m2/repository`                               | Mockito / ByteBuddy / Objenesis  |
| `BUILD_DIR`    | `<repo>/build/test-harness`                      | compile output (never the IDE `bin/`) |
| `RESULTS_DIR`  | `<repo>/build/test-results`                      | JUnit XML output (surefire-style) |

Because every location is an env var with no macOS-only assumption in the default path, the same
script runs on Linux CI (set the vars; no `-XstartOnFirstThread` unless `--swt`). This is what
`.github/workflows/ci.yml` calls — unmodified, in both its headless and its `xvfb` display lane.

## The two manifests (data, not code)

- **`osgi-excluded-tests.txt`** — the single source of truth for classes the default headless run
  **skips**: OSGi/Eclipse-singleton classes (e.g. `McpServerManagerTest`) that are entirely
  assumption-skipped headlessly, plus SWT/display-required classes (run those with `--swt`, or in
  Eclipse via `AllPluginTestsRunner`, or under `xvfb` on CI). Each line is a fully-qualified class
  name + a one-line reason.
- **`known-failing-tests.txt`** — a quarantine of classes that *run* headlessly but currently
  *fail* (pre-existing, tracked defects). They are still compiled, run, and written to XML
  (visible), but their failures **do not** gate the build — so a red exit always means a **new**
  regression. This list should shrink to empty as those defects are fixed.

When you add a `*Test.java`, it is picked up automatically. When a test legitimately cannot run
headlessly, add it to `osgi-excluded-tests.txt` with a reason — never silently.
