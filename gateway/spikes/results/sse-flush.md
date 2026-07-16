# P0-ENG receipt: server engine SSE flush timing (2026-07-16)

Route drips 10 SSE frames at 300ms intervals via respondBytesWriter+flush();
raw Socket client timestamps arrival. PASS = inter-arrival jitter vs the 300ms tick
stays < 100ms for every frame (i.e., no buffering/coalescing).

## netty
- frames received: 10/10
- arrival ms since first byte: [50, 348, 653, 958, 1259, 1560, 1864, 2169, 2474, 2779]
- inter-arrival deltas ms (expect ~300): [298, 305, 305, 301, 301, 304, 305, 305, 305]
- max jitter vs tick: 5ms -> PASS

## cio
- frames received: 10/10
- arrival ms since first byte: [1, 306, 612, 917, 1222, 1527, 1832, 2140, 2445, 2751]
- inter-arrival deltas ms (expect ~300): [305, 306, 305, 305, 305, 305, 308, 305, 306]
- max jitter vs tick: 8ms -> PASS

## Verdict
Both engines flush per-frame on this Ktor version. Plan default stays NETTY (documented CIO flush regressions history: ktor#1199, KTOR-7324); CIO empirically fine today.
