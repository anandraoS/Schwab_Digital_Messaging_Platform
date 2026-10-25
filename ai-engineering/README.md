# AI Engineering Workspace

How this repository is set up so that humans and AI coding assistants can work on it safely and consistently: context files, standards, checklists, reusable prompts/skills, a multi-agent workflow, and worked review examples.

## Provenance statement (read first)
This repository was authored with **Claude Code** (an agentic CLI), orchestrated by a lead agent that delegated sections to sub-agents, working from `docs/design/CONTRACT.md`. **No GitHub Copilot session, Copilot agent mode, Copilot code review, or any other third-party agent was run to produce these artifacts.** The files under `.github/copilot-instructions.md`, `.github/instructions/`, `.github/prompts/` and `AGENTS.md` are *authored configuration* demonstrating how such tools would be configured; they have not been exercised by those tools here. Where this workspace describes a workflow (e.g., independent security review), it describes the intended process, not a record that it happened.

## Contents
| Path | Purpose |
|---|---|
| `context-map.md` | Which doc to load for which task; repository map |
| `context/*.md` | Seven short, token-lean context modules with "load when" headers |
| `standards/*.md` | Java/Spring, Angular, API design, DB migration standards |
| `checklists/*.md` | Code review, security review, test generation, performance review, definition of done, release readiness |
| `debugging-workflows.md` | Symptom-driven debugging recipes |
| `templates/` | Feature spec template pointer and handoff-note template |
| `context-engineering.md` | Selection, token optimization, drift prevention, invariants list |
| `skills/*.md` | Reusable task playbooks |
| `multi-agent-workflow.md` | Roles, gates, independent review |
| `spec-driven-workflow.md` | Idea to production in 11 steps, with a worked example |
| `ai-code-review/` | 10 worked examples: flawed code, fix, rationale, regression test |
| `../specs/` | Specs (0001 priority aging, 0002 new provider adapter, template) |
| `../AGENTS.md` | Cross-tool agent instructions |
| `../.github/copilot-instructions.md`, `instructions/`, `prompts/` | Copilot-format configuration |

## Tool-specific format differences
| Mechanism | File(s) | Scope | Loaded | Notes |
|---|---|---|---|---|
| **AGENTS.md** (open convention read by many agents) | `AGENTS.md` at repo root (nested ones allowed in subfolders) | whole repo / subtree (nearest file wins) | automatically by supporting agents | plain Markdown, no front matter; keep short: build/test commands, conventions, boundaries |
| **GitHub Copilot repository instructions** | `.github/copilot-instructions.md` | whole repo | automatically in Copilot chat/agent/review where supported | plain Markdown; natural-language rules; keep under a couple of pages |
| **Copilot path-specific instructions** | `.github/instructions/*.instructions.md` | files matching `applyTo` glob in YAML front matter | automatically when the matching files are in context | front matter `applyTo: "**/*.java"`; optional `excludeAgent` |
| **Copilot prompt files** | `.github/prompts/*.prompt.md` | user-invoked reusable prompt (`/name` in chat) | on demand | front matter (e.g., `mode`/`agent`, `description`, `tools`) varies by Copilot version: verify against current docs |
| **Claude Code memory** | `CLAUDE.md` (project root, nested, and user-level), imports with `@path` | repo/subtree/user | automatically at session start | Markdown; hierarchical; this repo does not require a CLAUDE.md because `AGENTS.md` carries the shared rules (a one-line `CLAUDE.md` importing `@AGENTS.md` is a common bridge) |
| Claude Code skills / slash commands | `.claude/skills`, `.claude/commands` | on demand | not used here; the equivalent playbooks live in `ai-engineering/skills/` as plain Markdown so any tool (or human) can read them |
| Cursor/other | `.cursor/rules`, etc. | tool specific | out of scope | `AGENTS.md` is the portable baseline |

Design choice: single source of truth in `ai-engineering/context/*` and `docs/design/CONTRACT.md`; tool-specific files are thin pointers plus the highest-value rules, to avoid duplication and drift (see `context-engineering.md`).

## How to use
1. Start from `context-map.md` and load only the modules your task needs.
2. For features follow `spec-driven-workflow.md`; write the spec from `../specs/spec-template.md`.
3. Before opening a PR run the relevant checklist from `checklists/`.
4. For sensitive changes (auth, tenancy, callbacks, templates, state machine, retries) follow independent review in `multi-agent-workflow.md`.
5. Never silently override an ADR: write a superseding ADR.

## Honesty rules for AI-authored content
Never claim execution that did not occur; label "not executed"; no secrets (dummy values only); no real data; do not assert vendor behavior as fact; mark illustrative numbers.
