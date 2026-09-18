// The compaction feed, as strips: one per outcome in the totals bay, one per event in the tail bay,
// and the opened event swelled into its detail column.
//
// WHAT CHANGED FROM THE OLD INSTRUMENT, and why. The old feed printed outcomes as coloured pills and
// the tail as a table. A pill states its state in colour alone, which is the one thing this world
// refuses; a strip states it on a holder edge that ALWAYS prints its word, so a grayscale capture
// still says which compactions failed. The event tail keeps every field the table carried — the
// error text included — because the point of the feed is the one that went wrong.
//
// The component takes the payload as a prop rather than reading the store: a static render only ever
// sees a zustand store's initial state (CONTRACTS.md section 4), so a board that read the store
// could not be tested or captured from data.
import { useState } from 'react';
import type { CompactPayload, CompactRow } from '@shared/api';
import { fmtInt, fmtMs, timeAgo } from '@shared/lib';
import { Bay, Empty, Strip, StripField } from '@shared/ui';
import type { Edge } from '@shared/ui';
import { S } from './strings';
import './compact-feed.css';

/**
 * The daemon's outcome names, mapped to the holder edge they earn. The names are the compaction
 * layer's own (`gateway/compact`); anything unrecognised is amber rather than green, because an
 * unknown outcome is not a success.
 */
export function edgeFor(outcome: string): Edge {
  if (outcome.startsWith('model')) return 'green';
  if (outcome === 'empty_model' || outcome === 'stream_error' || outcome === 'upstream_error') return 'red';
  return 'amber';
}

function eventKey(row: CompactRow, index: number): string {
  return `${row.head}-${row.ts}-${index}`;
}

export function CompactFeed({ payload, sample = false }: { payload: CompactPayload; sample?: boolean }) {
  const [open, setOpen] = useState<string | null>(null);
  const outcomes = Object.entries(payload.stats.by_outcome);
  const tail = [...payload.stats.tail].reverse();
  const opened = tail.find((row, index) => eventKey(row, index) === open) ?? null;

  return (
    <div className="myx-cfeed">
      <div className="myx-cfeed-bays">
        <Bay
          label={S.outcomes}
          count={outcomes.length}
          empty={{ text: S.none, source: 'GET /api/compact' }}
        >
          {/* THE TOTAL IS A SPAN, NOT A WIDE FIRST FIELD (M1-73). It is one value stated across
              the whole row, so it declares the two tracks this bay's outcome rows use -- w=26+w=12
              -- and says span=2 so anything comparing first-field edges excludes it by
              declaration instead of by not looking. Measured before: this row's single w=12 field
              rendered 902.5px against the outcome rows' 617.5px, a 384px span across the bay. */}
          <Strip edge="grey" edgeLabel={S.total} ariaLabel={S.total}>
            <StripField w={38} span={2} label={S.total} value={fmtInt(payload.stats.total)} />
          </Strip>
          {outcomes.map(([outcome, count]) => (
            <Strip
              key={outcome}
              edge={edgeFor(outcome)}
              edgeLabel={outcome}
              ariaLabel={`${S.outcome} ${outcome}`}
            >
              <StripField w={26} label={S.outcome} value={outcome} mono={false} />
              <StripField w={12} label={S.count} value={fmtInt(count)} />
            </Strip>
          ))}
        </Bay>

        <Bay
          label={S.events}
          count={tail.length}
          empty={{ text: S.none, source: 'GET /api/compact' }}
        >
          {tail.map((row, index) => {
            const key = eventKey(row, index);
            // `unknown` IS THE RIGHT WORD (M1-69): the daemon did not report an outcome for this row, which
  // is "we asked and were not told" - not `none`, which would say the answer is zero and turn a
  // missing report into a reading.
  const outcome = row.outcome ?? 'unknown';
            return (
              <Strip
                key={key}
                edge={edgeFor(outcome)}
                edgeLabel={outcome}
                selected={open === key}
                onOpen={() => setOpen(open === key ? null : key)}
                ariaLabel={`${S.openEvent} ${outcome}`}
              >
                <StripField w={13} label={S.when} value={timeAgo(row.ts)} />
                <StripField w={18} label={S.head} value={row.head} mono={false} />
                <StripField w={20} label={S.outcome} value={outcome} mono={false} />
                <StripField w={13} label={S.chars} value={row.chars === undefined ? '' : fmtInt(row.chars)} />
                <StripField w={11} label={S.took} value={row.ms === undefined ? '' : fmtMs(row.ms)} />
              </Strip>
            );
          })}
        </Bay>
      </div>

      <aside className="myx-cfeed-detail" aria-label={S.detail}>
        {opened === null ? (
          <Empty text="no event opened" source={sample ? S.sample : S.live} />
        ) : (
          <>
            <Strip
              edge={edgeFor(opened.outcome ?? 'unknown')}
              edgeLabel={opened.outcome ?? 'unknown'}
              ariaLabel={S.detail}
            >
              <StripField w={13} label={S.when} value={new Date(opened.ts).toISOString().slice(11, 19)} />
              <StripField w={18} label={S.head} value={opened.head} mono={false} />
              <StripField w={13} label={S.chars} value={opened.chars === undefined ? '' : fmtInt(opened.chars)} />
              <StripField w={11} label={S.took} value={opened.ms === undefined ? '' : fmtMs(opened.ms)} />
              <StripField w={11} label={S.status} value={opened.status ?? ''} />
            </Strip>
            {opened.error === undefined ? null : (
              <p className="myx-cfeed-error" role="alert">{opened.error}</p>
            )}
          </>
        )}
      </aside>
    </div>
  );
}
