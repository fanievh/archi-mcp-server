#!/usr/bin/env bash
#
# Regenerate the drawio-to-archi slash command from its source prompt.
#
# WHY THIS EXISTS
#   The slash command is a SELF-CONTAINED COPY of prompts/drawio-to-archimate-model.md,
#   not a pointer to it — a slash command has to work without the repository present.
#   Two copies of one document drift, and a drifted copy is worse than no copy: the
#   user runs the command believing it matches the prompt they reviewed. Hand-syncing
#   is exactly the step that gets skipped, so this script does it mechanically.
#
# WHAT IT PRODUCES
#   .claude/commands/drawio-to-archi.md = frontmatter + argument-parsing preamble
#   + the source prompt from its "## ROLE" heading onward. The source prompt's own
#   top-level heading and paste-instruction comment are dropped: the frontmatter
#   supplies the title, and the arguments arrive via $ARGUMENTS rather than by paste.
#
# USAGE
#   tools/sync-drawio-command.sh          # rewrite the command copy
#   tools/sync-drawio-command.sh --check  # exit 1 if the copy is stale (for CI)
#
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SOURCE="$REPO_ROOT/prompts/drawio-to-archimate-model.md"
TARGET="$REPO_ROOT/.claude/commands/drawio-to-archi.md"

CHECK_ONLY=0
[[ "${1:-}" == "--check" ]] && CHECK_ONLY=1

if [[ ! -f "$SOURCE" ]]; then
    echo "FAIL: source prompt not found: $SOURCE" >&2
    exit 1
fi

# The source prompt must still carry the anchor this script splits on. If the
# heading is ever renamed, fail loudly rather than emit a truncated command file.
if ! grep -q '^## ROLE$' "$SOURCE"; then
    echo "FAIL: '## ROLE' anchor not found in $SOURCE — the split point moved." >&2
    echo "      Update this script's anchor before regenerating." >&2
    exit 1
fi

generate() {
    cat <<'HEADER'
---
description: Replicate a draw.io architecture diagram as an ArchiMate model — elements, relationships and a view — via the Archi MCP server
argument-hint: "<path-to.drawio> [semantic|mirror|both] [path-to-companion-doc] [icon-package...]"
---

<!-- SELF-CONTAINED COPY — generated from the source prompt at
     prompts/drawio-to-archimate-model.md in the source repository.
     Two installed copies may exist: ~/.claude/commands/drawio-to-archi.md (user-level) and
     <project>/.claude/commands/drawio-to-archi.md (project-level). If the source prompt
     changes, RE-SYNC both copies — they do not auto-update.
     Regenerate with: tools/sync-drawio-command.sh -->

**Arguments:** `$ARGUMENTS`

> **Parse `$ARGUMENTS` as `<drawio-path> [mode] [companion-doc] [icon-package...]`.**
>
> - **`<drawio-path>`** (required) — a `.drawio`, `.drawio.xml` or mxGraph `.xml` file. If absent, ask for it and stop.
> - **`[mode]`** — `semantic` (default), `mirror` or `both`. Any other token in this position is treated as the companion document.
> - **`[companion-doc]`** — path to the design document, HLD or solution description the diagram came from. Optional and high-value; when supplied, read it at Phase 3.
> - **`[icon-package...]`** — **one entry per vendor**, and there may be several: a **directory** holding an unpacked vendor icon set, or a **URL** to the vendor's package archive or download page. Optional, repeatable. Tell them apart from the companion document by shape: a document is a file, an icon package is a directory or a URL. A diagram carrying both AWS and Azure stencils needs **both** packages — supplying one silently drops every icon from the other vendor. When none is supplied, the prompt's own local search applies.
>
> **Preconditions:** the **Archi MCP Server** tools are connected and an Archi model is **open**. If either is missing, stop and say so.
>
> Bind the parsed values to the `{{DRAWIO_FILE}}`, `{{MODE}}`, `{{COMPANION_TEXT}}` and `{{ICON_PACKAGES}}` tokens below, then follow the prompt from Phase 0.

---

HEADER
    awk '/^## ROLE$/{found=1} found' "$SOURCE"
}

if [[ $CHECK_ONLY -eq 1 ]]; then
    if [[ ! -f "$TARGET" ]]; then
        echo "FAIL: command copy missing: $TARGET" >&2
        echo "      Run tools/sync-drawio-command.sh to generate it." >&2
        exit 1
    fi
    if ! diff -q <(generate) "$TARGET" >/dev/null; then
        echo "FAIL: $TARGET is out of sync with $SOURCE" >&2
        echo "      Run tools/sync-drawio-command.sh to regenerate it." >&2
        exit 1
    fi
    echo "OK: command copy is in sync with the source prompt."
    exit 0
fi

mkdir -p "$(dirname "$TARGET")"
generate > "$TARGET"
echo "Wrote $TARGET ($(wc -l < "$TARGET" | tr -d ' ') lines) from $(basename "$SOURCE")."
