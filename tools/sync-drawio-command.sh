#!/usr/bin/env bash
#
# Regenerate the drawio-to-archi slash command from its source prompt.
#
# WHAT IT PRODUCES
#   .claude/commands/drawio-to-archi.md = frontmatter + argument-parsing preamble
#   + the source prompt from its "## ROLE" heading onward. The source prompt's own
#   top-level heading and paste-instruction comment are dropped: the frontmatter
#   supplies the title, and the arguments arrive via $ARGUMENTS rather than by paste.
#   The body is carried VERBATIM — this command needs no transform.
#
# USAGE
#   tools/sync-drawio-command.sh          # rewrite the command copy
#   tools/sync-drawio-command.sh --check  # exit 1 if the copy is stale (for CI)
#
# The generate/check logic, and the reason --check reports NOT APPLICABLE on the
# published tree, live in the shared harness: tools/lib/sync-command.sh.
#
set -euo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/lib/sync-command.sh"

SOURCE_REL="prompts/drawio-to-archimate-model.md"
TARGET_REL=".claude/commands/drawio-to-archi.md"

emit_header() {
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
}

sync_command_main "$@"
