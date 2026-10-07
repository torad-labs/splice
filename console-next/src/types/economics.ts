// The daemon's token economics, one hour of SUMS per head (GET /api/economics).

/** One hour of a head's token economics. SUMS ONLY — the daemon deliberately ships no ratios,
 * so every rate on screen is derived here and stays recomputable when the window changes. */
export interface EconomicsBucket {
  hour: number;
  turns: number;
  in_tokens: number;
  cached_tokens: number;
  /** V4-86: the cache-WRITE half of in_tokens, disjoint from cached_tokens (the read half). Its
   * own field because it bills at the vendor's cache_write rate, not the input rate — and because
   * netting it into either of the other two would make a read and a write indistinguishable here.
   * Absent on a bucket the daemon loaded from a pre-V4-86 economics file, where it reads as 0. */
  cache_write_tokens: number;
  out_tokens: number;
  req_bytes: number;
  upstream_req_bytes: number;
  tools_eager: number;
  tools_deferred: number;
  /** Turns that REPORTED a tool partition. 0 on a dialect that cannot defer — which the ledger
   * must render as "n/a", never as a deferral rate of zero. */
  deferral_turns: number;
  rate_limited: number;
  /** V4-221: the hour's dollars, each turn priced by the daemon at its own model's card. Null is
   *  "not priced then", an hour recorded before the daemon priced turns, and never $0. Absent from
   *  a daemon older than V4-221, which read the same: not priced. */
  cost_usd?: number | null;
  /** Turns whose usage or declared prices could not support pricing; their dollars are not in cost_usd.
   *  Absent beside an absent cost_usd, when every turn of the hour is unpriced. */
  unpriced_turns?: number;
  /** Turns with no usage report, excluded from token and dollar sums. Absent on older daemons. */
  unreported_usage_turns?: number;
}

export interface HeadEconomics {
  key: string;
  label: string;
  /** The provider's own x-ratelimit-limit-tokens, or null where it sends none. A null ceiling
   * renders as "no ceiling known" — never as a guess. */
  ceiling_tokens: number | null;
  buckets: EconomicsBucket[];
  /** Present when this head's hours cannot be shown honestly: one plain sentence saying why, beside
   *  empty buckets. The other heads still answer. Absent on a head whose hours are its buckets. */
  unavailable?: string;
}

export interface EconomicsPayload {
  retention_hours: number;
  generated_at: number;
  heads: HeadEconomics[];
}
