# Prompts

Ready-to-use prompts for driving the **Archi MCP Server** with an LLM agent. Each file here is a self-contained source of truth: paste it into an agent session that has the Archi MCP tools connected, fill in any `{{PLACEHOLDER}}` tokens, and run.

## Available prompts

| Prompt | Purpose |
|--------|---------|
| [`repo-to-archimate-model.md`](repo-to-archimate-model.md) | Reverse-engineer a code repository into a full, evidence-marked **ArchiMate 3.2** model — elements, relationships, folders, views, and connections across as many layers as the code and docs support. |
| [`archimate-model-to-pdf-report.md`](archimate-model-to-pdf-report.md) | Generate a polished, print-ready **PDF architecture report** from any open Archi model — cover page with provenance, executive summary, every view (in optimal sequence) with captioned diagrams and element tables, a full element catalogue and relationship register, and an optional appendix embedding the model's own generating prompt. |
| [`drawio-to-archimate-model.md`](drawio-to-archimate-model.md) | Replicate a **draw.io** architecture diagram as an ArchiMate model — elements, relationships and a view, every concept marked with whether it was read or guessed. Two modes: `semantic` (layout from the ArchiMate recipes) and `mirror` (layout resembling the source diagram). Accepts the diagram's accompanying design document as an optional second input, which is what turns unlabelled arrows into real relationship types. |

### Reference implementation

`archimate-model-to-pdf-report.md` is tool-agnostic — any capable agent can drive it with the MCP query tools plus a PDF toolchain. It also ships with an optional, ready-to-run reference implementation that talks to the MCP server directly over its loopback HTTP endpoint:

| Script | Purpose |
|--------|---------|
| [`archimate-model-to-pdf-report.py`](archimate-model-to-pdf-report.py) | Standalone Python generator — harvests the open model over MCP, exports each view, and renders the PDF via WeasyPrint. Model-agnostic; validated across multiple architectures. |

```bash
# WeasyPrint is the only external dependency (pip install weasyprint).
python3 archimate-model-to-pdf-report.py --full \
    --session-log path/to/generating-prompt.txt   # optional: embeds it as Appendix C
```

Run with `--full` for 100 % element + relationship coverage (including elements/relationships not placed on any view). Use `--port` if the server is not on the default `18090`. It requires only the Python standard library plus the `weasyprint` CLI.

## How to use

1. Open (or create) a model in Archi with the MCP Server running and connected to your agent.
2. Open the prompt file and replace any `{{PLACEHOLDER}}` tokens (e.g. the repository path) with your values.
3. Paste the prompt body into the agent session and let it run. The prompts are written to be **iterative and resumable** — the open Archi model is the durable checkpoint, so an interrupted run can be continued by re-running.

## Relationship to slash commands

`repo-to-archimate-model.md` is also distributed as the `/repo-to-archi` Claude Code slash command, and `drawio-to-archimate-model.md` as `/drawio-to-archi`. The command files **embed a copy** of the prompt (plus an argument-parsing preamble) and do **not** auto-update. When you edit a prompt here, re-sync its command copies — this folder is the source of truth.

For `/drawio-to-archi` the sync is mechanical, so it need not be done by hand:

```bash
tools/sync-drawio-command.sh           # regenerate .claude/commands/drawio-to-archi.md
tools/sync-drawio-command.sh --check   # exit 1 if the copy has drifted
```
