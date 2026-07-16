---
name: gmr-agent
description: Run GMR (Geometric Multi-pass Reasoning) navigations. Produces blog posts, carousels, or legal memos from a topic or thesis through a 10-block toroidal pipeline. Use when the user asks to navigate a topic, run a study, or produce content.
tools: Bash, Read, Glob, Grep
model: sonnet
---

# GMR Agent

10-block pipeline: wake-up → anchor → vertex-scan → multi-pass → theta → phi → filter → verify → compose → build → verify.

## Entry point

`core/agents/navigation/index.ts` exports `runPipeline(opts: PipelineOptions): Promise<PipelineResult>`. The orchestrator lives in `core/agents/navigation/orchestrator/`; block implementations in `core/agents/navigation/blocks/`.

## CLI

```bash
npx tsx platform/runner/cli/entry.ts --topic "..." --format blog
npx tsx platform/runner/cli/entry.ts --thesis "..." --format blog
npx tsx platform/runner/cli/entry.ts --topic "..." --format blog --prompt "creative directive"
npx tsx platform/runner/cli/entry.ts --topic "..." --resume "anchor text"   # skip cached nav, blog-only
```

Or via the workspace script: `npm run pipeline --workspace=navigation -- --topic "..."`.

## Options

| Flag | Values | Default |
|------|--------|---------|
| `--topic` | Free text | — |
| `--thesis` | Free text | — |
| `--format` | `blog`, `carousel`, `memo` | `blog` |
| `--model` | `sonnet`, `opus`, `haiku` | `sonnet` |
| `--prompt` | Creative directive / angle | — |
| `--resume` | Anchor text from prior nav | — |

## Environment

Set in `.env` at repo root or in the runner Worker secrets (`platform/deploy/cloudflare-runner/wrangler.toml`):

- `ANTHROPIC_API_KEY` — Anthropic API access
- `PG_CONNECTION_STRING` — Neon PostgreSQL (job queue + block storage)
- `NEO4J_URI`, `NEO4J_USER`, `NEO4J_PASSWORD` — Graph brain (optional, degrades gracefully)
- `RUNNER_SECRET` — Bearer token for /run endpoint (api Worker presents this when triggering jobs)

## Structure

```
core/agents/navigation/     ← pure pipeline logic (no I/O surface)
├── index.ts                ← public API (runPipeline + types)
├── domain/                 ← pipeline I/O, block schemas — no internal deps
│   ├── pipeline.ts         ← PipelineOptions / PipelineResult
│   ├── context.ts          ← OrchestratorState
│   └── ...
├── blocks/
│   ├── 00-wake-up.ts
│   ├── navigation/         ← blocks 01–07 (the toroidal half)
│   │   ├── 01-anchor.ts
│   │   ├── 02-vertex-scan.ts
│   │   ├── 03-multi-pass.ts
│   │   ├── 04-theta.ts
│   │   ├── 05-phi.ts
│   │   ├── 06-filter.ts
│   │   ├── 07-verify.ts
│   │   ├── extractors.ts   ← prompt building between nav blocks
│   │   └── prompts/
│   └── blog/               ← blocks 08–10 (compose/build/verify)
│       ├── 08-compose.ts
│       ├── 09-build.ts
│       ├── 10-verify.ts
│       ├── build-tools/    ← HTML render helpers
│       └── prompts/
├── orchestrator/
│   ├── pipeline.ts         ← block dispatch state machine
│   ├── dispatch.ts         ← block execution + retry/escalation
│   ├── prompt.ts           ← prompt assembly per block
│   ├── nav-registry.ts     ← block name → factory map (nav side)
│   ├── blog-registry.ts    ← block name → factory map (blog side)
│   ├── nav-definitions.ts  ← block ordering + format gating
│   ├── nav-summary.ts      ← surviving-vertex extraction
│   ├── postprocess.ts      ← post-block transforms (filter, theta, etc.)
│   └── resume.ts           ← resume-from-anchor support
├── infrastructure/         ← LLM providers, brain, telemetry, models, costs
├── shared/                 ← cross-layer utilities
└── tests/                  ← integration + contract tests (testcontainers)

platform/runner/            ← I/O surface (CLI, HTTP, Cloudflare Worker, runtime)
├── cli/                    ← CLI entry + arg parsing
├── http/                   ← Express/Hono HTTP server (gmr-runner.fly.dev)
├── runtime/                ← async job-processor used by the api Worker
└── worker/                 ← Cloudflare Worker entry (runner.torad.ai)
```

Layering: `orchestrator → blocks → infrastructure → domain → shared`. External consumers (api Worker, CLI users, the Worker) import from `index.ts` only.

## Output formats

- `blog` — long-form HTML post (blocks 08-10)
- `carousel` — Instagram derivative slides (block 09's carousel branch)
- `memo` — paralegal/legal research memo (block 10 legal branch — see `docs/features/block-10-legal-memo/`)

`--format` is plumbed through `domain/`, every block, every adapter, and `runtime/db.ts`. Format-specific block bodies branch on the format flag.
