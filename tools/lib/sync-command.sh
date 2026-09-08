#!/usr/bin/env bash
#
# Shared harness for the slash-command sync scripts.
#
# WHY A SHARED HARNESS
#   Each slash command is a SELF-CONTAINED COPY of a prompt under prompts/, not a pointer
#   to it — a slash command has to work without the repository present. Two copies of one
#   document drift, and a drifted copy is worse than no copy: the user runs the command
#   believing it matches the prompt they reviewed. That is why the copies are generated.
#
#   There is more than one such command, and the LOGIC of generating and checking them is
#   identical. Giving each command its own full script would put two copies of THIS file
#   in the tree — the same defect one level up, and demonstrably not a theoretical one:
#   the not-applicable arm below was added on 2026-09-07 to fix a lane that was red by
#   construction on the published repository, and a second copy is exactly the thing that
#   would have kept the old behaviour. So the harness lives here once and each command
#   contributes only what actually differs: its source, its target, and its header.
#
# CONTRACT — a caller sources this file and then calls `sync_command_main "$@"`.
#   Required before the call:
#     SOURCE_REL      repo-relative path to the source prompt
#     TARGET_REL      repo-relative path to the generated command copy
#     emit_header()   prints the frontmatter + argument-parsing preamble
#   Optional:
#     ANCHOR          heading the body is taken from, inclusive (default "## ROLE")
#     transform_body  filter applied to the body; identity when undefined. Use it for a
#                     divergence the copy MUST carry — e.g. an $ARGUMENTS line standing in
#                     for the prompt's {{TOKEN}} placeholders.
#     assert_output   called with the path of the freshly generated text, BEFORE it is
#                     written or compared. This is where a transform's completeness is
#                     CHECKED rather than trusted: enumerating every site a transform must
#                     rewrite is a judgement, and a judgement silently rots when the source
#                     prompt grows a new one. Assert the property instead ("no {{TOKEN}}
#                     survives") and a missed site fails the run. Use `die`.
#     assert_source   called with the source path once the anchor is known, BEFORE any
#                     generation. Anything transform_body matches on must be asserted here:
#                     a transform runs inside a pipeline feeding a process substitution,
#                     where a non-zero exit is easy to lose, so a reworded source has to
#                     fail on this line rather than silently emit a copy with the
#                     divergence dropped. Use `die` for the message.
#
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
COMMAND_TREE="$REPO_ROOT/.claude"

# Fail with a message on stderr and a non-zero exit.
die() { printf '%s\n' "$@" >&2; exit 1; }

sync_command_main() {
    local check_only=0
    [[ "${1:-}" == "--check" ]] && check_only=1

    local source="$REPO_ROOT/$SOURCE_REL"
    local target="$COMMAND_TREE/${TARGET_REL#.claude/}"
    local anchor="${ANCHOR:-## ROLE}"
    local self="tools/$(basename "${0}")"

    [[ -f "$source" ]] || die "FAIL: source prompt not found: $source"

    # The source prompt must still carry the anchor the body is split on. If the heading is
    # ever renamed, fail loudly rather than emit a truncated command file.
    grep -q "^${anchor}\$" "$source" || die \
        "FAIL: '${anchor}' anchor not found in $source — the split point moved." \
        "      Update ANCHOR in $self before regenerating."

    # Deliberately OUTSIDE the generate pipeline — see the contract note on assert_source.
    declare -F assert_source >/dev/null && assert_source "$source"

    if [[ $check_only -eq 1 ]]; then
        # NOT APPLICABLE, not "pass": no `.claude/` tree at all means this checkout does not
        # distribute the command copies (the published repository — `.claude/` is culled at
        # publish time while .github/ and prompts/ both ship, so the copy cannot exist here).
        # Keyed on the TREE, never on $target: were this `[[ ! -f "$target" ]]`, deleting a
        # copy in a checkout that DOES carry `.claude/` would silence the guard instead of
        # failing it, which is the whole defect these scripts exist to prevent. Do not widen it.
        if [[ ! -d "$COMMAND_TREE" ]]; then
            echo "SKIP: no $COMMAND_TREE in this checkout — the command copy is not distributed here."
            echo "      Nothing to compare against $source; the sync gate does not apply."
            return 0
        fi
        [[ -f "$target" ]] || die \
            "FAIL: command copy missing: $target" \
            "      Run $self to generate it."
        if ! diff -q "$(sync_command_render "$source" "$anchor")" "$target" >/dev/null; then
            die "FAIL: $target is out of sync with $source" \
                "      Run $self to regenerate it."
        fi
        echo "OK: $(basename "$target") is in sync with $(basename "$source")."
        return 0
    fi

    mkdir -p "$(dirname "$target")"
    cp "$(sync_command_render "$source" "$anchor")" "$target"
    echo "Wrote $target ($(wc -l < "$target" | tr -d ' ') lines) from $(basename "$source")."
}

# Render once into a temp file, run assert_output over it, and echo the path. Rendering to
# a file rather than a process substitution is what lets assert_output run in the main path,
# where `die` actually stops the run.
sync_command_render() {
    local source="$1" anchor="$2"
    local rendered="${TMPDIR:-/tmp}/sync-command.$$.$(basename "$TARGET_REL")"
    sync_command_generate "$source" "$anchor" > "$rendered"
    declare -F assert_output >/dev/null && assert_output "$rendered"
    printf '%s' "$rendered"
}

# header + the source body from $anchor onward, through the caller's transform.
sync_command_generate() {
    local source="$1" anchor="$2"
    emit_header
    awk -v a="$anchor" '$0 == a {found=1} found' "$source" \
        | { if declare -F transform_body >/dev/null; then transform_body; else cat; fi; }
}
