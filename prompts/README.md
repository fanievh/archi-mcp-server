# Prompts

Ready-to-use prompts for driving the **Archi MCP Server** with an LLM agent. Each file here is a self-contained source of truth: paste it into an agent session that has the Archi MCP tools connected, fill in any `{{PLACEHOLDER}}` tokens, and run.

## Available prompts

| Prompt | Purpose |
|--------|---------|
| [`repo-to-archimate-model.md`](repo-to-archimate-model.md) | Reverse-engineer a code repository into a full, evidence-marked **ArchiMate 3.2** model — elements, relationships, folders, views, and connections across as many layers as the code and docs support. |

## How to use

1. Open (or create) a model in Archi with the MCP Server running and connected to your agent.
2. Open the prompt file and replace any `{{PLACEHOLDER}}` tokens (e.g. the repository path) with your values.
3. Paste the prompt body into the agent session and let it run. The prompts are written to be **iterative and resumable** — the open Archi model is the durable checkpoint, so an interrupted run can be continued by re-running.

## Relationship to slash commands

`repo-to-archimate-model.md` is also distributed as the `/repo-to-archi` Claude Code slash command. The command files **embed a copy** of this prompt (plus an argument token and a clone step) and do **not** auto-update. When you edit a prompt here, re-sync its command copies — this folder is the source of truth.
