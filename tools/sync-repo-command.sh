#!/usr/bin/env bash
#
# Regenerate the repo-to-archi slash command from its source prompt.
#
# WHAT IT PRODUCES
#   .claude/commands/repo-to-archi.md = frontmatter + argument-parsing preamble
#   + the source prompt from its "## ROLE" heading onward, with ONE deliberate
#   divergence applied to the body (see THE TRANSFORM).
#
# THE TRANSFORM — why this command needs one and drawio-to-archi does not
#   The source prompt takes its repository and ref through {{REPO_PATH}} / {{REPO_REF}}
#   placeholders, which a human fills in before pasting. A slash command receives its
#   input as $ARGUMENTS instead, and the preamble above already tells the model how to
#   parse it. Carrying the two placeholder bullets through verbatim would hand the model
#   two unfilled {{TOKEN}}s and a second, conflicting account of the checkout procedure.
#   So the INPUT section's two placeholder bullets collapse to one $ARGUMENTS bullet.
#
#   That divergence is the reason this copy cannot be gated by a plain `diff` against the
#   prompt, and the reason it went ungated for so long. It is a REPLACEMENT, not an
#   exception: everything else in the body is carried verbatim, and assert_source fails
#   the run if either bullet is reworded, so the transform can never silently become a
#   no-op that drops the divergence.
#
# USAGE
#   tools/sync-repo-command.sh          # rewrite the command copy
#   tools/sync-repo-command.sh --check  # exit 1 if the copy is stale (for CI)
#
# The generate/check logic, and the reason --check reports NOT APPLICABLE on the
# published tree, live in the shared harness: tools/lib/sync-command.sh.
#
set -euo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/lib/sync-command.sh"

SOURCE_REL="prompts/repo-to-archimate-model.md"
TARGET_REL=".claude/commands/repo-to-archi.md"

# The single $ARGUMENTS bullet that stands in for the prompt's two placeholder bullets.
read -r -d '' ARGUMENTS_BULLET <<'BULLET' || true
- **Repository & version:** `$ARGUMENTS`, parsed as `<repo> [ref]` (or `<repo>@<ref>`). `<repo>` = local path or git URL (empty → current working directory); optional `<ref>` = tag / release / branch / commit to model (empty → current checkout / default branch). See the checkout guidance above.
BULLET

# The Phase-4 read step names {{REPO_PATH}}/{{REPO_REF}} a THIRD time, outside the INPUT
# section. It is the site this transform originally missed: the INPUT pair is the obvious
# one and this one is 60 lines away, which is precisely why assert_output below checks the
# property ("no placeholder survives") instead of trusting this enumeration.
read -r -d '' PHASE4_READ_LINE <<'PHASE4' || true
Read the repo at the path resolved above (the temp clone / detached worktree if a `<ref>` was requested, otherwise the local path or current working directory) and extract architecture signals. Look at, in roughly this order:
PHASE4

# Both bullets the transform keys on must still exist, or the transform would quietly
# emit a copy carrying raw {{REPO_PATH}} / {{REPO_REF}} placeholders. Asserted here, in
# the main path, rather than inside the generate pipeline — see tools/lib/sync-command.sh.
assert_source() {
    local source="$1"
    grep -q '^- \*\*Repository:\*\* ' "$source" || die \
        "FAIL: the '- **Repository:**' bullet is gone from $source — the transform anchor moved." \
        "      Update the transform in tools/sync-repo-command.sh before regenerating."
    grep -q '^- \*\*Version / ref (optional):\*\* ' "$source" || die \
        "FAIL: the '- **Version / ref (optional):**' bullet is gone from $source — the transform anchor moved." \
        "      Update the transform in tools/sync-repo-command.sh before regenerating."
    grep -q '^Read the repo at `{{REPO_PATH}}`' "$source" || die \
        "FAIL: the Phase-4 'Read the repo at \`{{REPO_PATH}}\`' line is gone from $source — the transform anchor moved." \
        "      Update the transform in tools/sync-repo-command.sh before regenerating."
}

# The completeness check the enumeration above cannot make for itself. A slash command that
# ships a raw {{TOKEN}} hands the model an unfilled placeholder in place of its input, so a
# placeholder site added to the prompt later must fail this run rather than reach a user.
assert_output() {
    local rendered="$1" leaked
    leaked="$(grep -n '{{[A-Z_]*}}' "$rendered" || true)"
    [[ -z "$leaked" ]] || die \
        "FAIL: placeholder token(s) survived into the generated copy:" \
        "$leaked" \
        "      The source prompt names a placeholder at a site the transform in" \
        "      tools/sync-repo-command.sh does not rewrite. Add it there, with an" \
        "      assert_source anchor, and regenerate."
}

# Inside the INPUT section only: the Repository bullet becomes the $ARGUMENTS bullet and
# the Version/ref bullet is dropped. Scoped to the section so a later mention of either
# phrase elsewhere in the prompt is left untouched.
transform_body() {
    awk -v repl="$ARGUMENTS_BULLET" -v phase4="$PHASE4_READ_LINE" '
        /^## INPUT$/          { in_input = 1; print; next }
        in_input && /^## /    { in_input = 0 }
        in_input && /^- \*\*Repository:\*\* /                  { print repl; next }
        in_input && /^- \*\*Version \/ ref \(optional\):\*\* / { next }
        /^Read the repo at `{{REPO_PATH}}`/                     { print phase4; next }
                              { print }
    '
}

emit_header() {
    cat <<'HEADER'
---
description: Reverse-engineer a code repo into a full, evidence-marked ArchiMate model via the Archi MCP server
argument-hint: "[repo-path-or-url] [tag|release|branch|commit]  (defaults to current checkout / default branch)"
---

<!-- SELF-CONTAINED COPY — generated from the source prompt at
     prompts/repo-to-archimate-model.md in the source repository.
     Two installed copies exist: ~/.claude/commands/repo-to-archi.md (user-level) and
     <project>/.claude/commands/repo-to-archi.md (project-level). If the source prompt
     changes, RE-SYNC both copies — they do not auto-update. -->

# Generate a full ArchiMate model from a code repository

**Target repository:** `$ARGUMENTS`

> If no path was supplied above, use the **current working directory** as the repository. Preconditions: the **Archi MCP Server** tools are connected and an Archi model is **open**, and you have read access to the repository (filesystem/git). If the Archi tools or an open model are missing, stop and say so.
>
> **Parse `$ARGUMENTS` as `<repo> [ref]`** (or `<repo>@<ref>`): `<repo>` = a local path or remote git URL (empty → current working directory); optional **`<ref>`** = a **tag / release / branch / commit** to model (empty → the current checkout for a local path, or the default branch for a URL). Obtain the source on disk before analysing — the analysis reads files:
> - **Remote URL:** `git clone --depth 1 --branch <ref> <url> <tmp>` works for a **tag or branch**; for a bare **commit SHA**, `git clone <url> <tmp> && git -C <tmp> checkout <ref>` (no `--depth`). With no ref, `git clone --depth 1 <url> <tmp>`.
> - **Local path with a ref:** do **not** mutate the user's working tree — make a detached checkout: `git -C <repo> worktree add --detach <tmp> <ref>`; analyse `<tmp>`; then `git -C <repo> worktree remove <tmp>`.
> - **Local path, no ref:** analyse the working tree as-is.
> - **Private repos** need credentials already configured (`gh auth`, an SSH key, or a token in the URL). If a clone/checkout fails for auth or an unknown ref, **stop and say so** — do not fall back to the web API (partial + rate-limited).
> - **Record the resolved ref/commit** in the model (a model property or the root note) and in the final report, so the model states which version it documents. **Delete the temp clone / worktree** when the model is built (the ArchiMate model lives in Archi, not in the checkout). The Archi model and MCP server are local and unaffected.

---

HEADER
}

sync_command_main "$@"
