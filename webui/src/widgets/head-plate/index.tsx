// The bench: one instrument plate per head, reading GET /api/heads (2s poll,
// started by the fleet page) + GET /api/usage for the per-head meter. Start /
// stop / restart are the ONLY mutating actions in the app; stop and restart
// interrupt a live session, so both route through the two-step ConfirmBtn.
import { useState } from 'react';
import { restartHead, startHead, stopHead, useHeads } from '@entities/heads';
import { useUsage } from '@entities/usage';
import type { HeadActionResult, HeadStatus, WarnLevel } from '@shared/api';
import { fmtTokens } from '@shared/lib';
import { Btn, ConfirmBtn, ErrorNote, Metric, MeterBar, SkeletonRows, Stale, StatusPill } from '@shared/ui';
import type { PillTone } from '@shared/ui';

type Action = 'start' | 'stop' | 'restart';

function statusTone(head: HeadStatus): PillTone {
  if (!head.running) return 'mute';
  if (head.versionMatch === false) return 'amber';
  return 'pos';
}

function statusText(head: HeadStatus): string {
  if (!head.running) return 'down';
  if (head.versionMatch === false) return 'stale'; // running on the wrong version; the number sits in the metrics row
  return 'live';
}

function meterTone(level: WarnLevel): 'pos' | 'amber' | 'neg' {
  if (level === 'critical') return 'neg';
  if (level === 'warn') return 'amber';
  return 'pos';
}

function HeadPlate({ head, lastUpdated }: { head: HeadStatus; lastUpdated: number | null }) {
  const usage = useUsage((s) => s.data?.heads.find((h) => h.key === head.key)?.usage ?? null);
  const [busy, setBusy] = useState<Action | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [note, setNote] = useState<string | null>(null);

  const run = async (action: Action, fn: (key: string) => Promise<HeadActionResult>) => {
    setBusy(action);
    setError(null);
    try {
      const result = await fn(head.key);
      setNote(result.note ?? null);
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setBusy(null);
    }
  };

  return (
    <article className="myx-plate">
      <header className="myx-plate-head">
        <div>
          <div className="myx-plate-label">{head.label}</div>
          <div className="myx-plate-sub">{head.authKind} · :{head.port}</div>
        </div>
        <div className="myx-plate-head-right">
          <Stale lastUpdated={lastUpdated} />
          <StatusPill tone={statusTone(head)}>{statusText(head)}</StatusPill>
        </div>
      </header>

      <div className="myx-plate-metrics">
        <Metric label="version" value={head.version ?? 'unknown'} />
        {head.mode && head.mode !== head.name ? <Metric label="mode" value={head.mode} tone="amber" /> : null}
        {head.running && head.gate ? (
          <Metric label="gate inflight / queued" value={`${head.gate.inflight} / ${head.gate.queued}`} />
        ) : null}
        {head.running ? (
          <Metric
            label="errors (local / provider)"
            value={`${head.health.localOriginErrors} / ${head.health.providerErrors}`}
          />
        ) : null}
      </div>

      <div className="myx-plate-usage">
        {!usage ? (
          <p className="myx-plate-usage-none">no usage tracking</p>
        ) : usage.warn.source === 'none' ? (
          // No rate-limit headroom and no configured cap: a 0% meter would read as
          // "barely used" when it is really N tokens uncapped. Show the count plainly.
          <p className="myx-plate-usage-row">
            <span>{fmtTokens(usage.output_tokens_5h)} tokens in 5h</span>
            <span className="myx-plate-usage-none">no cap set</span>
          </p>
        ) : (
          <>
            <MeterBar pct={usage.warn.pct} tone={meterTone(usage.warn.level)} />
            <p className="myx-plate-usage-row">
              <span>{usage.warn.pct}% used</span>
              <span>{fmtTokens(usage.output_tokens_5h)} tokens</span>
              {usage.warn.reset ? <span>resets {usage.warn.reset}</span> : null}
            </p>
          </>
        )}
      </div>

      <div className="myx-plate-actions">
        {!head.running ? (
          <Btn kind="primary" busy={busy === 'start'} onClick={() => void run('start', startHead)}>start</Btn>
        ) : (
          <>
            <Btn kind="control" busy={busy === 'restart'} onClick={() => void run('restart', restartHead)}>restart</Btn>
            <ConfirmBtn busy={busy === 'stop'} onConfirm={() => void run('stop', stopHead)}>stop</ConfirmBtn>
          </>
        )}
      </div>

      {error ? <ErrorNote message={error} /> : null}
      {note ? <p className="myx-plate-note">{note}</p> : null}
    </article>
  );
}

function PlateSkeleton() {
  return (
    <article className="myx-plate" aria-hidden="true">
      <SkeletonRows rows={4} cols={3} />
    </article>
  );
}

export function HeadPlates() {
  const { data, error, loading, lastUpdated } = useHeads((s) => s);

  return (
    <>
      {error ? <ErrorNote message={error} /> : null}
      <div className="myx-fleet-grid">
        {loading && !data ? Array.from({ length: 2 }, (_, i) => <PlateSkeleton key={i} />) : null}
        {data?.map((head) => <HeadPlate key={head.key} head={head} lastUpdated={lastUpdated} />)}
      </div>
    </>
  );
}
