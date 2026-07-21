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

    for f in $(grep -rl 'PORT-OF' gateway/ --include='*.kt' | sort); do
      head -1 "$f" | grep -q '^// PORT-OF:' && echo "$f"; done

| # | File |
| --- | --- |
| 1 | gateway/app/src/main/kotlin/splice/app/DaemonLock.kt |
| 2 | gateway/control/src/main/kotlin/splice/control/ControlServer.kt |
| 3 | gateway/control/src/main/kotlin/splice/control/LaunchService.kt |
| 4 | gateway/control/src/main/kotlin/splice/control/StatuslineRenderer.kt |
| 5 | gateway/control/src/test/kotlin/ControlServerTest.kt |
| 6 | gateway/core/src/main/kotlin/splice/core/config/ConfigService.kt |
| 7 | gateway/core/src/main/kotlin/splice/core/config/Knob.kt |
| 8 | gateway/core/src/main/kotlin/splice/core/config/MgmtKey.kt |
| 9 | gateway/core/src/main/kotlin/splice/core/config/StatePaths.kt |
| 10 | gateway/core/src/main/kotlin/splice/core/launch/ClaudeConfigMaterializer.kt |
| 11 | gateway/core/src/main/kotlin/splice/core/model/ModelCatalog.kt |
| 12 | gateway/core/src/main/kotlin/splice/core/reasoning/Replay.kt |
| 13 | gateway/core/src/main/kotlin/splice/core/turn/PickedText.kt |
| 14 | gateway/core/src/main/kotlin/splice/core/turn/ReasoningThresholds.kt |
| 15 | gateway/core/src/main/kotlin/splice/core/usage/UsageWarn.kt |
| 16 | gateway/core/src/main/kotlin/splice/core/Versions.kt |
| 17 | gateway/core/src/main/kotlin/splice/core/wire/AnthropicRequest.kt |
| 18 | gateway/core/src/test/kotlin/AnthropicParseTest.kt |
| 19 | gateway/core/src/test/kotlin/ClaudeConfigMaterializerTest.kt |
| 20 | gateway/core/src/test/kotlin/ConfigServiceTest.kt |
| 21 | gateway/core/src/test/kotlin/ModelCatalogTest.kt |
| 22 | gateway/dialect-openai-responses/src/main/kotlin/splice/dialect/responses/Harvested.kt |
| 23 | gateway/dialect-openai-responses/src/main/kotlin/splice/dialect/responses/ResponsesRequestBuilder.kt |
| 24 | gateway/dialect-openai-responses/src/main/kotlin/splice/dialect/responses/ResponsesStreamTranslator.kt |
| 25 | gateway/dialect-openai-responses/src/test/kotlin/ResponsesRequestBuilderTest.kt |
| 26 | gateway/dialect-openai-responses/src/test/kotlin/ResponsesStreamTranslatorTest.kt |
| 27 | gateway/gateway/src/main/kotlin/splice/gateway/compact/Compact.kt |
| 28 | gateway/gateway/src/main/kotlin/splice/gateway/head/HeadServer.kt |
| 29 | gateway/gateway/src/main/kotlin/splice/gateway/pipeline/TurnPipeline.kt |
| 30 | gateway/gateway/src/main/kotlin/splice/gateway/reasoning/Mirror.kt |
| 31 | gateway/gateway/src/main/kotlin/splice/gateway/usage/UsageHud.kt |
| 32 | gateway/gateway/src/main/kotlin/splice/gateway/wire/SseEmitter.kt |
| 33 | gateway/gateway/src/testFixtures/kotlin/mock/MockChatGptUpstream.kt |
| 34 | gateway/gateway/src/test/kotlin/CompactTest.kt |
| 35 | gateway/gateway/src/test/kotlin/head/HeadServerIntegrationTest.kt |
| 36 | gateway/gateway/src/test/kotlin/mock/ScenarioIntegrationTest.kt |
| 37 | gateway/gateway/src/test/kotlin/ReplayMirrorTest.kt |
| 38 | gateway/gateway/src/test/kotlin/SseEmitterTest.kt |
| 39 | gateway/gateway/src/test/kotlin/UsageTest.kt |
| 40 | gateway/provider-codex/src/main/kotlin/splice/provider/codex/CodexAuthProvider.kt |
| 41 | gateway/provider-codex/src/main/kotlin/splice/provider/codex/CodexOAuth.kt |
| 42 | gateway/provider-codex/src/test/kotlin/CodexAuthTest.kt |
| 43 | gateway/provider-spi/src/main/kotlin/splice/spi/InflightGate.kt |
| 44 | gateway/provider-spi/src/main/kotlin/splice/spi/SingleFlight.kt |
| 45 | gateway/provider-spi/src/main/kotlin/splice/spi/UpstreamClient.kt |
| 46 | gateway/provider-spi/src/main/kotlin/splice/spi/UpstreamFailureClassifier.kt |
| 47 | gateway/provider-spi/src/main/kotlin/splice/spi/Watchdog.kt |
| 48 | gateway/provider-spi/src/test/kotlin/InflightGateTest.kt |
| 49 | gateway/provider-spi/src/test/kotlin/SseReaderTest.kt |
| 50 | gateway/provider-spi/src/test/kotlin/UpstreamFailureClassifierTest.kt |
| 51 | gateway/provider-spi/src/test/kotlin/WatchdogTest.kt |
