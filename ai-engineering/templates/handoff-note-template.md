# Handoff Note Template

Used when work passes between agents/people/sessions (the next reader has no memory of yours). Keep under one page. Save as `docs/handoffs/YYYY-MM-DD-topic.md` or paste into the PR.

```
# Handoff: <topic>            Date: <YYYY-MM-DD>   From: <role/agent>   To: <role/agent>

## Goal
<one sentence; link to spec/issue>

## State
- Done: <bullets with file paths>
- In progress: <what, where it stopped, why>
- Not started: <bullets>

## Decisions made (and why)
- <decision> (ADR link if cross-cutting). Any deviation from CONTRACT/ADR: <where recorded: docs/design/DEVIATIONS.md>

## Verified vs not verified
- Executed: <exact command> => <result, date>
- NOT executed: <items> (reason)

## Gotchas
- <environment quirks, flaky areas, traps>

## Next steps (ordered)
1. ...

## Open questions / risks
- ...

## Review needed
- Independent review required for: <sensitive areas touched> (reviewer: <role>)
- Files to inspect first: <paths>
```
Rules: facts only; no secrets; no claims of execution without output; list every file touched.
