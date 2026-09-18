# ORACLE CAPTURE — dev/campaigns/manifest.py laws, taken 2026-09-18 before V4-143 Phase D2 deletes it.
# HEAD at capture: 2c0c0c60e016cb1b5fc88ed3c5f9285c7ffdf1e5
# python3: Python 3.13.7
#
# WHY THIS EXISTS. The .ts replacement is proven byte-for-byte against the .py, and that proof
# expires the moment the .py is deleted — after Phase D there is no oracle and the strongest
# check anyone can still run is the weaker one. A proof you could have taken is not a proof.
#
# THE AGGREGATION SPEC, in one line: exactly ONE distinct law text is cross-ledger (the concept
# #945 banner, present in 9 ledgers), so the aggregate keeps its first occurrence and drops 8
# copies, and zero duplicate lines remain. 258 summed -> 250 aggregated.

LEDGER                     LAWS
bug-sweep                     6
ci-hardening                 12
claude-head                  11
cli-wizard                   11
drift-repair                 10
head-decoupling              18
kotlin-gateway               11
oss-release                   9
proxy-hardening              24
reasoning-cache               6
v0.4.0                       59
web-console                  75
ws-transport                  7
-- summed                   259
-- aggregate (no path)      251
-- deduped away               8

sha256 of aggregate: e58146b83da750ed4e014d2c3376a26e2f2056bae83211899b193b4270788277

## THIS IS A SNAPSHOT, NOT A PIN — and it proved itself so during the capture

Between the differential run and this capture, splice-design committed a law to web-console.toml.
The counts moved underneath me: 250 -> 251 aggregated, 258 -> 259 summed, web-console 74 -> 75.
Live campaigns add laws; any check that pins a NUMBER or a FILE HASH here goes stale the next time
anyone runs `add-law`, and a stale pin that nobody can explain is how a wall gets disabled.

What did NOT move is the shape: still exactly one cross-ledger law text, still 8 copies dropped,
still zero duplicates in the output. So the invariant is STRUCTURAL and that is what a gate leg
must assert:

    aggregate(no path)  ==  dedup-first-wins( concat( laws(L) for L in glob(dev/campaigns/*.toml) ) )

The ledger set comes from globbing the directory, never a hand list, so a new campaign is covered
the day it is created. That check is true today, stays true as laws are added, and — the reason it
is the one worth building — it remains checkable AFTER manifest.py is deleted, because it compares
the .ts against itself across two invocations rather than against a vanished oracle.

Use these files as the worked example of the spec and as evidence for the numbers in the row's
notes. Do not assert equality against them.
