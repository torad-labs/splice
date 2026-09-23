# Provenance

This repository (`splice`) is the productization of a proxy that translates the
Anthropic Messages API to the ChatGPT Codex Responses API. Its code has an
inheritance chain that predates this repo. This document records that chain, the
external upstream, its license, and the clearance status of inherited material.

## Inheritance chain

```
EXTERNAL upstream (third-party, MIT)
  codex-for-claude-code - Yusang Park
  single-file scripts/codex-proxy.mjs (~21 KB)
        |  forked locally (npm package fork)
        v
LOCAL fork (codex-for-claude-code local fork)
  local codex-proxy v25 -> v26 -> v27 -> v28 -> v29
  (six local versions in two days, 2026-07-13; grew to a 1783-line single file)
        |  splice decomposition
        v
splice - codex-proxy v30 (this repo)
  npm workspaces server/ + webui/, 18 server modules,
  walls-first ast-grep policy, management plane, committed dashboard
        |  Kotlin port (PORT-OF headers @ pre-public-port-baseline)
        v
gateway/ Kotlin gateway
  51 PORT-OF-marked .kt files citing server/src modules @ pre-public-port-baseline (SELF-inheritance)
```

Two distinct kinds of inheritance appear in the tree and MUST NOT be conflated:

- EXTERNAL inheritance: the local fork descends from the third-party npm package
  `codex-for-claude-code`. This carries the upstream author's copyright and MIT
  license terms (see below).
- SELF-inheritance: the `gateway/` Kotlin modules are ports of THIS repo's own
  `server/src/*.mjs` modules, pinned at revision `pre-public-port-baseline`. The PORT-OF headers
  cite in-repo files, not external code; they create no third-party obligation.

## Upstream identification (RESOLVED)

The external upstream is conclusively identified:

- Package: `codex-for-claude-code` (npm)
- Author: Yusang Park
- npm: https://www.npmjs.com/package/codex-for-claude-code
- Repository: https://github.com/yusang-park/codex-for-claude-code
- License: MIT
- Current published version: 0.2.6 (2026-04-26); 9 versions, first published
  2026-04-22 (0.1.0).

Corroborating evidence (local vs upstream):

- CHANGELOG "Left behind (deliberate)" names five upstream files -
  `claude-wrapper`, `set-model-mode`, `build-codex-server`, `lib/auto-update`,
  `bin/claude-codex` - all present in the upstream package tree
  (`scripts/claude-wrapper.mjs`, `scripts/set-model-mode.mjs`,
  `scripts/build-codex-server.mjs`, `scripts/lib/auto-update.mjs`,
  `bin/claude-codex.mjs`). A 5/5 filename match.
- Upstream ships a single-file `scripts/codex-proxy.mjs` (~21 KB) - the same
  single-file shape the local v25..v29 line grew from before the v30 split.
- Behavioral fingerprints match: proxy on `127.0.0.1:3099`, Codex OAuth from
  `~/.codex/auth.json`, isolated config dir `~/.claude-codex`, model options
  written to `~/.claude-codex/.claude.json`, `pkill -f codex-proxy.mjs`.

## Required notices

The upstream is MIT-licensed. MIT requires the copyright notice and permission
notice be retained in all copies or substantial portions of the software. The
operator MUST retain, for inherited material, the upstream MIT block:

    MIT License
    Copyright (c) Yusang Park
    (full MIT permission + warranty-disclaimer text)

The recommended mechanism is a `NOTICES` / `THIRD-PARTY-LICENSES` file (or an
appended section in `LICENSE`) carrying the upstream MIT block attributed to
Yusang Park, referenced from this document.

## RESOLVED - fork source pinned (2026-07-19)

All previously-open items are closed; the evidence is the operator's original
fork working copy, recovered at a local path outside this repository:

1. Exact forked revision: upstream commit
   `d66dd54d7baed0059d2fc34280b409ee6c6730df` ("docs: add npm badges to
   README", `git describe` = `v0.2.6-1-gd66dd54`), package.json version
   `0.2.6`, cloned from
   `https://github.com/yusang-park/codex-for-claude-code.git`. The inherited
   single-file proxy (`scripts/codex-proxy.mjs`) is present at that revision.
2. License at the pinned revision: verified verbatim — `MIT License,
   Copyright (c) 2026 Yusang Park`.
3. Notice retention: discharged — `THIRD_PARTY_NOTICES.md` carries the
   upstream MIT block with attribution and this pinned revision.

Residual counsel note: MIT attribution plus notice retention discharges the
license obligation for the inherited lineage (the v30 decomposition and the
Kotlin port are derivative works of the same MIT code); a counsel
confirmation of that standard reading remains a reasonable pre-1.0 checkbox.

## MIT LICENSE scope statement

This repository's `LICENSE` (MIT, Copyright (c) 2026 Marcos) asserts rights over
the ORIGINAL splice work - the v30 decomposition, the module architecture, the
walls/gates, the management plane, the WebUI, and the Kotlin gateway. It does NOT
launder or extinguish the inherited upstream material: code descended from
`codex-for-claude-code` remains subject to the upstream MIT terms and its
author's copyright, and the repo LICENSE cannot grant rights over what it did not
originate. Original work and inherited work coexist; both notices apply.

## PORT-OF Kotlin inventory (SELF-inheritance @ pre-public-port-baseline)

51 Kotlin files carry `PORT-OF` headers citing this repo's own `server/src`
modules at revision `pre-public-port-baseline`. Regenerate with:

    for f in $(git ls-files '*.kt' | xargs grep -l '^// PORT-OF:' | sort); do
      head -1 "$f" | grep -q '^// PORT-OF:' && echo "$f"; done

| # | File |
| --- | --- |
| 1 | app/src/main/kotlin/splice/app/daemon/DaemonLock.kt |
| 2 | daemon/control/src/main/kotlin/splice/control/ControlServer.kt |
| 3 | daemon/control/src/main/kotlin/splice/control/LaunchService.kt |
| 4 | daemon/control/src/main/kotlin/splice/control/StatuslineRenderer.kt |
| 5 | daemon/control/src/test/kotlin/splice/control/ControlServerTest.kt |
| 6 | core/src/main/kotlin/splice/core/config/ConfigService.kt |
| 7 | core/src/main/kotlin/splice/core/config/Knob.kt |
| 8 | core/src/main/kotlin/splice/core/config/MgmtKey.kt |
| 9 | core/src/main/kotlin/splice/core/config/StatePaths.kt |
| 10 | integrations/claude-code/src/main/kotlin/splice/client/ClaudeConfigMaterializer.kt |
| 11 | core/src/main/kotlin/splice/core/model/ModelCatalog.kt |
| 12 | core/src/main/kotlin/splice/core/reasoning/Replay.kt |
| 13 | core/src/main/kotlin/splice/core/turn/PickedText.kt |
| 14 | core/src/main/kotlin/splice/core/turn/ReasoningThresholds.kt |
| 15 | core/src/main/kotlin/splice/core/usage/UsageWarn.kt |
| 16 | core/src/main/kotlin/splice/core/Versions.kt |
| 17 | core/src/main/kotlin/splice/core/wire/AnthropicRequest.kt |
| 18 | core/src/test/kotlin/splice/core/parse/AnthropicParseTest.kt |
| 19 | integrations/claude-code/src/test/kotlin/splice/client/ClaudeConfigMaterializerTest.kt |
| 20 | core/src/test/kotlin/splice/core/config/ConfigServiceTest.kt |
| 21 | core/src/test/kotlin/splice/core/model/ModelCatalogTest.kt |
| 22 | integrations/dialects/openai-responses/src/main/kotlin/splice/dialect/responses/stream/Harvested.kt |
| 23 | integrations/dialects/openai-responses/src/main/kotlin/splice/dialect/responses/request/ResponsesRequestBuilder.kt |
| 24 | integrations/dialects/openai-responses/src/main/kotlin/splice/dialect/responses/stream/ResponsesStreamTranslator.kt |
| 25 | integrations/dialects/openai-responses/src/test/kotlin/splice/dialect/responses/request/ResponsesRequestBuilderTest.kt |
| 26 | integrations/dialects/openai-responses/src/test/kotlin/splice/dialect/responses/stream/ResponsesStreamTranslatorTest.kt |
| 27 | features/turns/src/main/kotlin/splice/head/compact/Compact.kt |
| 28 | features/turns/src/main/kotlin/splice/head/HeadServer.kt |
| 29 | features/turns/src/main/kotlin/splice/head/pipeline/TurnPipeline.kt |
| 30 | features/turns/src/main/kotlin/splice/head/reasoning/Mirror.kt |
| 31 | features/turns/src/main/kotlin/splice/head/usage/UsageHud.kt |
| 32 | features/turns/src/main/kotlin/splice/head/wire/SseEmitter.kt |
| 33 | features/turns/src/testFixtures/kotlin/splice/head/MockChatGptUpstream.kt |
| 34 | features/turns/src/test/kotlin/splice/head/compact/CompactTest.kt |
| 35 | features/turns/src/test/kotlin/splice/head/HeadServerIntegrationTest.kt |
| 36 | features/turns/src/test/kotlin/splice/head/ScenarioIntegrationTest.kt |
| 37 | features/turns/src/test/kotlin/splice/head/reasoning/ReplayMirrorTest.kt |
| 38 | features/turns/src/test/kotlin/splice/head/wire/SseEmitterTest.kt |
| 39 | features/turns/src/test/kotlin/splice/head/usage/UsageTest.kt |
| 40 | integrations/providers/codex/src/main/kotlin/splice/provider/codex/CodexAuthProvider.kt |
| 41 | integrations/providers/codex/src/main/kotlin/splice/provider/codex/CodexOAuth.kt |
| 42 | integrations/providers/codex/src/test/kotlin/splice/provider/codex/CodexAuthTest.kt |
| 43 | integrations/upstream/src/main/kotlin/splice/upstream/retry/InflightGate.kt |
| 44 | integrations/upstream/src/main/kotlin/splice/upstream/retry/SingleFlight.kt |
| 45 | integrations/upstream/src/main/kotlin/splice/upstream/transport/UpstreamClient.kt |
| 46 | integrations/upstream/src/main/kotlin/splice/upstream/failure/UpstreamFailureClassifier.kt |
| 47 | integrations/upstream/src/main/kotlin/splice/upstream/retry/Watchdog.kt |
| 48 | integrations/upstream/src/test/kotlin/splice/upstream/retry/InflightGateTest.kt |
| 49 | integrations/upstream/src/test/kotlin/splice/upstream/sse/SseReaderTest.kt |
| 50 | integrations/upstream/src/test/kotlin/splice/upstream/failure/UpstreamFailureClassifierTest.kt |
| 51 | integrations/upstream/src/test/kotlin/splice/upstream/retry/WatchdogTest.kt |
