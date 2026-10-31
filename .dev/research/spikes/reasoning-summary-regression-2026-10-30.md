# GPT-6.1 Sol subscription reasoning summaries — 2026-10-30

Status: partial mitigation. Paragraph summaries returned in two of three live
code-mode checks after changing the subscription head from `auto` to `concise`.
One check still returned headings only. The original regression remains open.

## Applied configuration

The existing Codex subscription head was updated with:

```toml
[heads.claudex.overrides]
summary = "concise"
```

Only that head's summary setting changed. The configuration was backed up and
atomically replaced, followed by `splice restart --now`. Effective configuration
confirmed `concise`, reasoning replay enabled, and mirroring disabled. All eleven
heads were running and healthy after restart. No paid API route, prompt rewrite,
request-shape change, source default, or new jar was introduced by this mitigation.

The daemon's open jar matched the previously installed artifact:
`6100901f3d5d41771342f7190c38f58f6c063cfe18701e4264a86194d75b30a6`.

## Request comparison

The corrected matrix used the same coding fixture, GPT-6.1 Sol, `xhigh` effort,
low verbosity, fresh sessions, and an actual tool call followed by its result.
Each row covers three delivery settings: omitted, `sequential`, and
`sequential_cutoff`. All eighteen cases completed the tool round trip. Final
outgoing frames and upstream responses were captured before comparing them with
client Thinking blocks.

| Request shape | Summary | Cases | Cases with paragraph text |
|---|---|---:|---:|
| Responses Lite | `detailed` | 3 | 0 |
| Responses Lite | `auto` | 3 | 0 |
| Responses Lite | `concise` | 3 | 0 |
| Full Responses | `detailed` | 3 | 0 |
| Full Responses | `auto` | 3 | 0 |
| Full Responses | `concise` | 3 | 2 |

The two full/concise successes contained 479 and 478 non-heading characters;
the client retained the same amounts. Subsequent full/concise repetitions
produced paragraphs in only two of six cases, so the full request shape was not
adopted.

Additional controls covered response verbosity, reasoning effort, an explicit
output budget, ordinary function calls with code mode disabled, and paragraph
instructions. None consistently restored the former behavior. `detailed` and
`auto` remained heading-only in the completed controls. `concise` returned
paragraphs intermittently, including after native code-mode tool calls.

The subscription endpoint rejected `reasoning.generate_summary`, `ultra` effort,
and `minimal` effort in these probes; rejected requests are not evidence of
heading-only model output. No production effort setting was changed.

## Live verification

After applying the setting through the normal configuration path and restarting,
all three live checks completed a native code-mode tool-result round trip:

| Fixture | Non-heading summary characters |
|---|---:|
| Tenant cache | 1,136 |
| Retry helper | 0 |
| Streaming reader | 485 |

Paragraph-bearing Thinking blocks were inspected as model-written sentences,
rather than tool source or gateway status messages. These results establish a
live improvement, not a consistently restoring fix or the original cause.

## Evidence handling and remaining uncertainty

The initial matrix batch was excluded: its full-shape prewarm supplied tools
without retaining them on later generation frames. The corrected batch retained
tools and instructions on every full-shape frame. Tool completion failures were
kept separate from successful model responses.

The response audit inspected summary deltas, text completion, part completion,
completed reasoning items, and terminal snapshots. It compared the longest
received version per item/part without double-counting repeated copies. A parser
cannot recover paragraph content absent from all those carriers.

The earlier parser corrections are separate changes already pushed:

- `243ec9897`: recover sparse summary completion tails.
- `e5b04721c`: retain buffer ownership through terminal cleanup.

Historical subscription transcripts confirm that paragraph summaries existed
before the regression. Exact account continuity across the later OAuth refresh
could not be established because no older credential backup was available.
The original cause is therefore not assigned to either the provider or a local
request change by this investigation.

Exact requests, responses, scripts, local configuration backups, and per-case
receipts remain in the operator's private investigation artifacts outside the
repository. Raw captures and credentials are not part of this note.
