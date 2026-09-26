// Compare (V4-239): what `splice models` prints, each provider's published list against splice.toml,
// read from GET /api/models/upstream.
//
// ON DEMAND ONLY. Every read asks the providers themselves with the credential the daemon holds, so
// the panel reads once when the operator presses Compare, again only on Compare again, and never on a
// poll. The answer carries no credential: the daemon strips a models_url's user info and query before
// it leaves (UpstreamModelsRoute.shown), so the url printed here is where it asked and nothing more.
//
// Every declared row is printed with its verdict; the models a provider serves that no row declares
// are collapsed behind a reveal with their count, as the verb shows eight and counts the rest.
import { useEffect, useState } from 'react';
import { readUpstreamModels } from '@entities/model';
import type { RosterVerdict, UpstreamModelsPayload, UpstreamProvider, UpstreamRow } from '@entities/model';
import { Blank, Fault, Key } from '@shared/controls';
import { ABSENT, fmtTokens } from '@shared/lib';
import { Badge, DataTable, Empty, KeyValue, Reveal } from '@shared/ui';
import type { Column, Tone } from '@shared/ui';
import { H, S } from './strings';

/** The verdicts of a served model no row declares. */
const UNDECLARED: ReadonlySet<RosterVerdict> = new Set(['new', 'excluded']);

const VERDICT_TONE: Record<RosterVerdict, Tone> = {
  served: 'ok',
  capped: 'neutral',
  'over-ceiling': 'danger',
  unserved: 'danger',
  new: 'accent',
  excluded: 'neutral',
};

function messageOf(err: unknown): string {
  return err instanceof Error ? err.message : String(err);
}

/** One Compare: the comparison, which clears the fault, or the read's failure in the daemon's words. */
export function compareUpstream(
  onPayload: (payload: UpstreamModelsPayload) => void,
  onFault: (fault: string | null) => void,
): Promise<void> {
  return readUpstreamModels().then(
    (payload) => {
      onPayload(payload);
      onFault(null);
    },
    (err: unknown) => onFault(messageOf(err)),
  );
}

const windowText = (value: number | null): string => (value === null ? ABSENT : fmtTokens(value));

const COLUMNS: Column<UpstreamRow>[] = [
  { key: 'id', label: S.model, cell: (row) => row.id, mono: true, wrap: true },
  { key: 'verdict', label: S.verdict, cell: (row) => <Badge tone={VERDICT_TONE[row.verdict]} quiet>{S.verdictName[row.verdict]}</Badge> },
  { key: 'declared', label: S.declaredWindow, cell: (row) => windowText(row.declared_window), align: 'end', mono: true },
  { key: 'upstream', label: S.providerWindow, cell: (row) => windowText(row.upstream_window), align: 'end', mono: true },
  { key: 'note', label: S.note, cell: (row) => row.note, wrap: true },
];

function standing(provider: UpstreamProvider): { tone: Tone; text: string } {
  if (provider.roster === 'unreadable') return { tone: 'danger', text: S.unreadable };
  if (provider.roster === 'unpublished') return { tone: 'neutral', text: S.noList };
  return provider.agrees ? { tone: 'ok', text: S.agrees } : { tone: 'warn', text: S.needsDecision };
}

/** One provider: where it was asked, how it stands, its declared rows, and the rest collapsed. */
function ProviderLists({ provider }: { provider: UpstreamProvider }) {
  const declared = provider.rows.filter((row) => !UNDECLARED.has(row.verdict));
  // Discovered first, then those kept out: the verb's order.
  const undeclared = provider.rows
    .filter((row) => UNDECLARED.has(row.verdict))
    .sort((left, right) => Number(left.verdict === 'excluded') - Number(right.verdict === 'excluded'));
  const { tone, text } = standing(provider);
  return (
    <section className="myx-md-up" aria-label={`${S.provider} ${provider.key}`}>
      <div className="myx-md-up-head">
        <strong>{provider.key}</strong>
        <Badge tone={tone}>{text}</Badge>
      </div>
      <KeyValue
        rows={[
          [S.provider, `${provider.dialect} · ${provider.url}`],
          ...(provider.reason === undefined ? [] : [[S.reason, provider.reason] as const]),
        ]}
      />
      {declared.length === 0 ? null : (
        <DataTable columns={COLUMNS} rows={declared} rowKey={(row) => row.id} label={`${S.models} ${provider.key}`} />
      )}
      {undeclared.length === 0 ? null : (
        <Reveal label={`${S.undeclared} ${undeclared.length}`}>
          <DataTable columns={COLUMNS} rows={undeclared} rowKey={(row) => row.id} label={`${S.undeclared} ${provider.key}`} />
        </Reveal>
      )}
    </section>
  );
}

/** The comparison, every provider in splice.toml's order. */
export function UpstreamBoard({ payload }: { payload: UpstreamModelsPayload }) {
  if (payload.providers.length === 0) return <Empty text={S.noProviders} source={H.noProviders} />;
  return (
    <div className="myx-md-ups">
      {payload.providers.map((provider) => <ProviderLists key={provider.key} provider={provider} />)}
    </div>
  );
}

/** The Compare panel's body: one read when it opens, and one more per Compare again. */
export function UpstreamCompare() {
  const [payload, setPayload] = useState<UpstreamModelsPayload | null>(null);
  const [fault, setFault] = useState<string | null>(null);
  const [busy, setBusy] = useState(true);
  const compare = () => {
    setBusy(true);
    void compareUpstream(setPayload, setFault).finally(() => setBusy(false));
  };
  // The panel mounts when the operator presses Compare: that press is the read, once.
  useEffect(compare, []);
  return (
    <div className="myx-md-compare">
      {fault === null ? null : <Fault message={fault} />}
      {payload === null ? (busy ? <Blank strips={3} /> : null) : <UpstreamBoard payload={payload} />}
      <Key onClick={compare} disabled={busy} busy={busy}>{S.compareAgain}</Key>
    </div>
  );
}
