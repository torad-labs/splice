# mythos

Productized claudex/claudithos proxy stack. Two heads: **claudex** (Codex
upstream, :3099) and **claudithos** (Claude upstream, :3098). npm workspaces:
`server/` (Node, no build step) + `webui/` (React 19 + Vite, single-file build
served by the proxy at `/dashboard`).

## Status

Phase 0 (walls) complete: architecture rules are enforced at write time and at
the gate before any server code exists. See `.rules/README.md` for the wall
doctrine and `AGENTS.md` (pending migration) for invariants L1–L4.

## Gates

```
npm run gate:rules   # ast-grep scan (tree) + ast-grep test (rule red/green)
npm run test:hooks   # orchestrator routing tests
```
