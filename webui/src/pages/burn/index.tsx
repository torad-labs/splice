// BURN — the quota instrument.
//
// THE DECISION THIS PAGE CLOSES: "is this session about to cost me the week, and what do I
// change?" On 2026-08-01 that question had no surface anywhere in splice: an account went 0% to
// 100% in twelve hours while every gauge the dashboard already had (cache hit rate, output
// tokens/5h, per-stage latency) stayed green. It stayed green because it was measuring the wrong
// things — the hit rate ROSE from 68% to 89% across the exact window that drained the plan.
//
// So the page leads with total input against the ceiling, and shows the hit rate only in the
// ledger, deliberately demoted, because a high hit rate is what made the drain look safe.
//
// COPY NOTE: the em-dash gate (webui-no-emdash-ui-text) is locked, so every user-visible string
// here uses a colon or a full stop, and absent data reads "n/a" rather than a dash placeholder.
import { useEffect, useState } from 'react';
import {
  startEconomicsPolling, useEconomics,
  sum, within, burn, hourly, hitRate, writeRate, amplification, perTurn, toolSurface, wireDelta,
} from '@entities/economics';
import { ErrorNote, EmptyState, Stale } from '@shared/ui';
import { fmtInt } from '@shared/lib';
import type { HeadEconomics } from '@shared/api';
import './burn.css';

const WEEK_HOURS = 168;
const TRACE_HOURS = 48;
/** Vermilion is spent ONLY here. An earlier cut alarmed whenever exhaustion landed inside the
 *  week (<168h), which fired on nearly every active head — a head with 134h of headroom lit up
 *  exactly like one with 17h, and an alarm that is always on is not an alarm. One day of
 *  remaining headroom is the threshold where an operator can still act on it. */
const ALARM_HOURS = 24;
const DAY_MS = 86_400_000;
const HOUR_MS = 3_600_000;
const CEILING_KEY = 'myx-burn-ceiling';
const NA = 'n/a';

/** Tokens at plan scale: k/M/B. fmtTokens caps at M and would render a weekly figure as
 *  "1700.00M", which is unreadable at exactly the magnitude that matters here. */
function fmtBig(n: number): string {
  if (n >= 1e9) return `${(n / 1e9).toFixed(2)}B`;
  if (n >= 1e6) return `${(n / 1e6).toFixed(1)}M`;
  if (n >= 1e3) return `${Math.round(n / 1e3)}k`;
  return String(Math.round(n));
}

function fmtBytes(n: number): string {
  const abs = Math.abs(n);
  const sign = n > 0 ? '+' : n < 0 ? '-' : '';
  if (abs >= 1024) return `${sign}${(abs / 1024).toFixed(1)} KB`;
  return `${sign}${Math.round(abs)} B`;
}

/** Operator-asserted ceilings, per head. Kept in localStorage rather than the daemon config on
 *  purpose: this is a claim about the operator's PLAN, not about splice, and it must stay visibly
 *  distinct from a provider-reported limit. The UI labels the two differently and never lets an
 *  asserted number masquerade as measured. */
function readOverrides(): Record<string, number> {
  try {
    const raw = localStorage.getItem(CEILING_KEY);
    return raw ? (JSON.parse(raw) as Record<string, number>) : {};
  } catch {
    return {};
  }
}

export function BurnPage() {
  const [overrides, setOverrides] = useState<Record<string, number>>(readOverrides);
  // Select STABLE references only. Deriving inside the selector returns a fresh array each call,
  // which useSyncExternalStore reads as a changed snapshot and loops (the fleet-banner lesson).
  const data = useEconomics((s) => s.data);
  const error = useEconomics((s) => s.error);
  const lastUpdated = useEconomics((s) => s.lastUpdated);

  useEffect(() => startEconomicsPolling(), []);

  const setCeiling = (key: string) => {
    const entered = window.prompt(
      `Weekly input-token ceiling for "${key}".\n\n` +
        "Your plan's limit, in tokens (e.g. 1770000000). This is recorded as an ASSERTED " +
        'ceiling, which splice cannot verify. Blank clears it.',
      String(overrides[key] ?? ''),
    );
    if (entered === null) return;
    const parsed = Number(entered.replace(/[_,\s]/g, ''));
    const clear = entered.trim() === '' || !Number.isFinite(parsed) || parsed <= 0;
    // Rebuild without the key rather than deleting it (no-dynamic-delete).
    const next: Record<string, number> = Object.fromEntries(
      Object.entries(overrides).filter(([k]) => k !== key),
    );
    if (!clear) next[key] = parsed;
    setOverrides(next);
    try {
      localStorage.setItem(CEILING_KEY, JSON.stringify(next));
    } catch { /* private mode: the override lives for this session in component state */ }
  };

  if (error) return <ErrorNote message={error} />;
  if (!data) return <EmptyState label="reading the quota rollup" />;

  const now = data.generated_at;
  const active = data.heads.filter((h) => h.buckets.length > 0);

  if (active.length === 0) {
    return <EmptyState label="no turns recorded yet: the rollup fills from the next turn onward" />;
  }

  return (
    <div className="bn">
      {active.map((h) => (
        <Scale key={h.key} head={h} now={now} override={overrides[h.key]} onSetCeiling={setCeiling} />
      ))}
      <Ledger heads={active} now={now} />
      <Seam heads={active} now={now} />
      <Trace heads={active} now={now} />
      <Stale lastUpdated={lastUpdated} />
    </div>
  );
}

// ── BAND 1 · THE SCALE ───────────────────────────────────────────────────────

function Scale({ head, now, override, onSetCeiling }: {
  head: HeadEconomics;
  now: number;
  override: number | undefined;
  onSetCeiling: (key: string) => void;
}) {
  // A provider-reported ceiling always wins an operator-asserted one: measured beats claimed.
  const reported = head.ceiling_tokens;
  const effective = reported ?? override ?? null;
  const b = burn({ ...head, ceiling_tokens: effective }, now);
  const pct = b.fraction === null ? null : Math.min(1, b.fraction);

  // The single alarm condition on this page: less than a day of headroom at the current rate.
  const alarm = b.hoursToExhaustion !== null && b.hoursToExhaustion < ALARM_HOURS;
  const spentPct = effective !== null && effective > 0 ? b.spent / effective : 0;
  // The MARK is drawn whenever exhaustion lands inside the visible horizon; only its COLOUR
  // carries the alarm. Suppressing the mark below the alarm threshold would hide the very
  // information an operator uses to avoid ever reaching it.
  //
  // Strictly INSIDE the bar: the track clips its overflow, so a mark computed at exactly 100%
  // renders as a zero-width element with a clipped flag. Beyond the horizon it is also the wrong
  // claim to make — the headroom sentence below already says it, and says it honestly.
  const projRaw = b.hoursToExhaustion !== null && b.exhaustsAt !== null
    ? (spentPct + b.hoursToExhaustion / WEEK_HOURS) * 100
    : null;
  const projLeft = projRaw !== null && projRaw < 100 ? projRaw : null;

  const kind = reported !== null
    ? { cls: 'is-reported', text: 'ceiling reported by provider' }
    : override !== undefined
      ? { cls: 'is-asserted', text: 'ceiling asserted by operator' }
      : { cls: 'is-absent', text: 'no ceiling known' };

  return (
    <section className="bn-scale-band" aria-labelledby={`bn-scale-${head.key}`}>
      <div className="bn-scale-head">
        <div>
          <span className={`bn-figure${alarm ? ' is-alarm' : ''}`}>
            {pct === null ? fmtBig(b.spent) : `${Math.round(pct * 100)}%`}
          </span>
          <span className="bn-figure-sub" id={`bn-scale-${head.key}`}>
            {head.label} · input tokens, 7 days
          </span>
        </div>
        <div className="bn-scale-right">
          {fmtBig(b.spent)}{effective !== null ? ` / ${fmtBig(effective)}` : ''}
          <br />
          <span className={`bn-ceiling-kind ${kind.cls}`}>{kind.text}</span>
        </div>
      </div>

      <div
        className="bn-track"
        role="meter"
        aria-valuemin={0}
        aria-valuemax={effective ?? undefined}
        aria-valuenow={b.spent}
        aria-label={`${head.label} weekly input tokens consumed`}
      >
        {pct !== null && <div className={`bn-fill${alarm ? ' is-alarm' : ''}`} style={{ width: `${pct * 100}%` }} />}
        {projLeft !== null && (
          <div className={`bn-proj${alarm ? ' is-alarm' : ''}`} style={{ left: `${projLeft}%` }}>
            <span className="bn-proj-flag">
              exhausts {new Date(b.exhaustsAt ?? now).toLocaleString([], {
                weekday: 'short', hour: '2-digit', minute: '2-digit',
              })}
            </span>
          </div>
        )}
      </div>

      <div className="bn-ticks" aria-hidden="true">
        {Array.from({ length: 8 }, (_, i) => (
          <span key={i} className="bn-tick" style={{ left: `${(i / 7) * 100}%` }}>
            {i === 7 ? 'now' : new Date(now - (7 - i) * DAY_MS).toLocaleDateString([], { weekday: 'short' })}
          </span>
        ))}
      </div>

      <p className="bn-rate">
        burning <b>{fmtBig(b.ratePerHour)}</b> tokens/hour over the last 6h
        {b.hoursToExhaustion === null
          ? ' · set a ceiling to project exhaustion'
          : Number.isFinite(b.hoursToExhaustion)
            ? ` · ${b.hoursToExhaustion < 1 ? '<1' : Math.round(b.hoursToExhaustion)}h of headroom left`
            : ' · idle'}
        <button type="button" className="bn-ceiling-set" onClick={() => onSetCeiling(head.key)}>
          {override !== undefined ? 'edit ceiling' : 'set ceiling'}
        </button>
      </p>
    </section>
  );
}

// ── BAND 2 · THE LEDGER ──────────────────────────────────────────────────────

function Ledger({ heads, now }: { heads: HeadEconomics[]; now: number }) {
  return (
    <section className="bn-band">
      <h2>the ledger · 7 days</h2>
      <table className="bn-ledger">
        <caption>per-turn economics by head</caption>
        <thead>
          <tr>
            <th scope="col">head</th>
            <th scope="col">turns</th>
            <th scope="col">input</th>
            <th scope="col">in/turn</th>
            <th scope="col">hit</th>
            <th scope="col">write</th>
            <th scope="col">amp</th>
            <th scope="col">tools</th>
            <th scope="col">429</th>
          </tr>
        </thead>
        <tbody>
          {heads.map((h) => {
            const t = sum(within(h.buckets, WEEK_HOURS, now));
            const hit = hitRate(t);
            const write = writeRate(t);
            const amp = amplification(t);
            const per = perTurn(t);
            const tools = toolSurface(t);
            return (
              <tr key={h.key}>
                <td>{h.label}</td>
                <td>{fmtInt(t.turns)}</td>
                <td>{fmtBig(t.inTokens)}</td>
                <td>{per === null ? <span className="bn-none">{NA}</span> : fmtBig(per)}</td>
                <td>{hit === null ? <span className="bn-none">{NA}</span> : `${Math.round(hit * 100)}%`}</td>
                {/* The cache-WRITE share of the same total. A head whose dialect reports no
                    cache-creation bucket sits at 0%, which is a fact about that dialect. */}
                <td>{write === null ? <span className="bn-none">{NA}</span> : `${Math.round(write * 100)}%`}</td>
                <td>{amp === null ? <span className="bn-none">{NA}</span> : `${Math.round(amp)}:1`}</td>
                {/* "n/a" means this dialect CANNOT defer, which is a finding, not a zero. */}
                <td>
                  {tools === null
                    ? <span className="bn-none">{NA}</span>
                    : `${Math.round(tools.eager)}/${Math.round(tools.total)}`}
                </td>
                <td className={t.rateLimited > 0 ? 'myx-ink-neg' : undefined}>{fmtInt(t.rateLimited)}</td>
              </tr>
            );
          })}
        </tbody>
      </table>
      <p className="bn-caption">
        <b>amp</b> is input tokens burned per output token: the read-amplification of a tool loop,
        and why turn COUNT dominates cost. <b>tools</b> is eager/total, so a head showing “n/a” has
        no tool deferral in its dialect and sends the whole surface every turn. <b>hit</b> and{' '}
        <b>write</b> are the read and the WRITE halves of that same input, and both sit late on
        purpose: cached tokens still bill in full, and a written one bills at the vendor's higher
        cache_write rate. A rising write share is a prefix being rebuilt rather than re-read.
      </p>
    </section>
  );
}

// ── BAND 3 · THE SEAM ────────────────────────────────────────────────────────

function Seam({ heads, now }: { heads: HeadEconomics[]; now: number }) {
  const rows = heads.map((h) => {
    const t = sum(within(h.buckets, WEEK_HOURS, now));
    return { head: h, delta: wireDelta(t), tools: toolSurface(t) };
  });
  const scale = Math.max(1, ...rows.map((r) => Math.abs(r.delta ?? 0)));

  return (
    <section className="bn-band">
      <h2>the seam · what splice does to a request</h2>
      <div className="bn-seam">
        {rows.map(({ head, delta, tools }) => {
          const d = delta ?? 0;
          const frac = Math.min(1, Math.abs(d) / scale) * 50;
          return (
            <div className="bn-seam-row" key={head.key}>
              <span className="bn-seam-label">{head.label}</span>
              <div className="bn-axis">
                <div
                  className={`bn-bar ${d >= 0 ? 'is-add' : 'is-cut'}`}
                  style={d >= 0 ? { left: '50%', width: `${frac}%` } : { right: '50%', width: `${frac}%` }}
                />
              </div>
              <span className="bn-seam-note">
                {fmtBytes(d)}/turn
                {tools !== null && ` · ${Math.round(tools.eager)}/${Math.round(tools.total)} tools`}
              </span>
            </div>
          );
        })}
      </div>
      <p className="bn-caption">
        Mean bytes a turn gains or loses between the client request and the upstream one. Left of
        the axis is removed (tool deferral); right is added (reasoning envelopes). A head sitting
        right of zero is one whose envelopes now outweigh everything its deferral removed. The two
        cannot be separated further without per-section instrumentation, so this is the NET only.
      </p>
    </section>
  );
}

// ── BAND 4 · THE TRACE ───────────────────────────────────────────────────────

function Trace({ heads, now }: { heads: HeadEconomics[]; now: number }) {
  const series = heads.map((h) => ({ head: h, values: hourly(h.buckets, TRACE_HOURS, now) }));
  const step = 100 / (TRACE_HOURS - 1);
  const endHour = Math.floor(now / HOUR_MS) * HOUR_MS;

  return (
    <section className="bn-band">
      <h2>the trace · {TRACE_HOURS}h</h2>
      {series.map(({ head, values }) => {
        // Each head scales to its OWN peak. A shared peak flattened every quiet head into a
        // dead line against the busiest one AND printed that head's peak in their legend, which
        // was simply false. This band is about SHAPE (is it ramping?); magnitude is compared in
        // the ledger, and the printed peak below keeps it readable here.
        const peak = Math.max(1, ...values);
        const pts = values.map((v, i) => `${i * step},${100 - (v / peak) * 100}`).join(' ');
        const limited = within(head.buckets, TRACE_HOURS, now).filter((b) => b.rate_limited > 0);
        return (
          <div key={head.key}>
            <svg
              className="bn-trace"
              viewBox="0 0 100 100"
              preserveAspectRatio="none"
              role="img"
              aria-label={
                `${head.label}: hourly input tokens over the last ${TRACE_HOURS} hours, peak ${fmtBig(peak)}`
              }
            >
              <polygon className="bn-trace-fill" points={`0,100 ${pts} 100,100`} />
              <polyline className="bn-trace-line" points={pts} vectorEffect="non-scaling-stroke" />
              {/* Every hour that hit a 429: the exact moment the plan said no. */}
              {limited.map((b) => (
                <rect
                  key={b.hour}
                  className="bn-trace-429"
                  x={(TRACE_HOURS - 1 - (endHour - b.hour) / HOUR_MS) * step - 0.4}
                  y={0}
                  width={0.8}
                  height={100}
                />
              ))}
            </svg>
            <div className="bn-legend">
              <span>{head.label}</span>
              <span>peak {fmtBig(peak)}/h</span>
              {limited.length > 0 && <span className="myx-ink-neg">{limited.length}h rate-limited</span>}
            </div>
          </div>
        );
      })}
    </section>
  );
}
