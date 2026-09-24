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
import { ABSENT, fmtInt, fmtMs, timeAgo } from '@shared/lib';
import { Key } from '@shared/controls';
import { Bay, HolderEdge, Strip, StripField } from '@shared/ui';
import type { Edge } from '@shared/ui';
import { cx } from '@shared/lib';
import { CLIENT_INSTRUCTIONS, OUTCOME_WORDS, S } from './strings';
import './compact-feed.css';

/** What an outcome MEANT, as the three states this world's holder edge has: a summary was produced,
 *  something needs a look, it failed. The names matched here are the compaction layer's own
 *  (`gateway/compact`); anything unrecognised is `warn` rather than `ok`, because an outcome we do
 *  not recognise is not a success. */
export type CompactState = 'ok' | 'warn' | 'fail';

export function stateOf(outcome: string): CompactState {
  if (outcome.startsWith('model')) return 'ok';
  if (outcome === 'empty_model' || outcome === 'stream_error' || outcome === 'upstream_error') return 'fail';
  return 'warn';
}

/** THE COLOUR AND THE WORD COME FROM ONE READING OF THE OUTCOME, so they cannot disagree. Before
 *  this the edge took its colour from `edgeFor` and its word from the outcome name itself, which
 *  is how a red strip could be labelled with a green strip's prefix. */
const EDGE: Record<CompactState, Edge> = { ok: 'green', warn: 'amber', fail: 'red' };

/** The daemon's outcome names, mapped to the holder edge they earn. Kept as the widget's public
 *  mapping — a page that wants the same colour asks the same function. */
export function edgeFor(outcome: string): Edge {
  return EDGE[stateOf(outcome)];
}

/** An outcome as a reader says it: the word for a name the daemon is known to write, and the
 *  daemon's own spelling, underscores read as spaces, for a name this console has not met. */
export function outcomeText(outcome: string): string {
  return OUTCOME_WORDS[outcome] ?? outcome.replaceAll('_', ' ');
}

/** One outcome's part of all compactions, to one decimal under 10% so a rare failure still reads. */
export function shareText(count: number, total: number): string {
  if (total <= 0) return ABSENT;
  const pct = (count / total) * 100;
  if (pct > 0 && pct < 0.1) return '<0.1%';
  return `${pct < 10 ? pct.toFixed(1) : Math.round(pct)}%`;
}

function eventKey(row: CompactRow, index: number): string {
  return `${row.head}-${row.ts}-${index}`;
}

/** The column widths, in ch, named once so the name row and the cells under it cannot drift apart.
 *  They were inline on the strips before this row; a fields row repeating the numbers by hand is
 *  two lists checking each other, which is the shape §24 names and this campaign has paid for. */
const OUTCOME = 26;
const COUNT = 12;
const SHARE = 10;
const WHEN = 13;
const HEAD = 18;
const EVENT = 22;
const CHARS = 15;
const TOOK = 11;

/** Where the empty racks point a reader who has never seen a compaction. */
const NONE = { text: S.none, source: 'claude code compacts a session when its context fills; each one lands here' };

/** A rack's column names, once, at the same ch widths as the cells they name — the `fields` row
 *  Bay has shipped since m1 and doctor already uses (m1 design review B9).
 *
 *  THE GROWTH IS THE HALF THAT IS EASY TO MISS: `strip-field.tsx` sets flexGrow to the field's OWN
 *  ch so cells share their rack's slack in proportion to their declared widths (M1-73), and a name
 *  fixed at `w ch` therefore drifts off the column under it — further the more slack the rack has,
 *  and this page's outcomes rack renders 26ch as 847px. The name takes the same growth.
 *
 *  It is a third copy of doctor's helper, and that is deliberate rather than unnoticed: the shared
 *  primitive question is already filed for m3 planning (M2-28), and a widget reaching into a page
 *  for it would be the FSD boundary violation the walls refuse. */
function ColumnNames({ columns }: { columns: readonly { w: number; label: string }[] }) {
  return (
    <>
      {columns.map((column) => (
        <span key={column.label} className="myx-cfeed-col" style={{ width: `${column.w}ch`, flexGrow: column.w }}>
          {column.label}
        </span>
      ))}
    </>
  );
}

/** Which instructions a compaction ran under: a rule's source label as core wrote it, or the
 *  client's own when no rule applied (the daemon writes `client` for that). */
function instructionsText(source: string | undefined): string {
  if (source === undefined) return ABSENT;
  return source === 'client' ? CLIENT_INSTRUCTIONS : source;
}

export function CompactFeed({ payload, sample = false }: { payload: CompactPayload; sample?: boolean }) {
  const [open, setOpen] = useState<string | null>(null);
  // Most common first: the daemon's map order put three failure kinds above the 65% that worked.
  const outcomes = Object.entries(payload.stats.by_outcome).sort(([, a], [, b]) => b - a);
  const tail = [...payload.stats.tail].reverse();
  const opened = tail.find((row, index) => eventKey(row, index) === open) ?? null;

  // THE RESTING COLUMN COLLAPSES RATHER THAN UNMOUNTING (M1-117, the idiom of M1-116). The body
  // grid's second track is 0 until an event is opened; this class is what widens it, and the
  // transition on grid-template-columns is what performs the --dur-2 swell CONTRACTS section 6
  // asks for. An unmounted column has nothing to animate from, which is why the instrument that
  // cited the swell while choosing unmount could not have made it.
  // AND THE COMMENT LIVES HERE, ABOVE THE RETURN, RATHER THAN AS THE FIRST CHILD OF IT: a JSX
  // comment is only a comment INSIDE an element. First child of `return (`, the brace opens an
  // object literal, the parser reads the block comment as an empty object, and tsc wants `)` and
  // gets an identifier -- TS1005 plus a cascade. It broke the tree-wide leg for every seat for
  // the length of one row, and I did not see it because I had filtered tsc's output with a grep
  // for the one error I already knew about. THE FILTER IS THE LESSON: a censored gate reads
  // clean, and the only reason this was caught is that another seat ran the same leg and read
  // ALL of it.
  return (
    <div className={cx('myx-cfeed', opened !== null && 'myx-cfeed-open')}>
      <div className="myx-cfeed-bays">
        <Bay
          label={S.outcomes}
          count={outcomes.length}
          // The fixture's own mark (CONTRACTS.md section 4: a grey `sample data` edge in the bay
          // label). It used to ride as the closed detail column's empty, which M3-03 removed.
          {...(sample ? { actions: <HolderEdge state="grey" label={S.sample} /> } : {})}
          empty={NONE}
          fields={(
            <ColumnNames
              columns={[{ w: OUTCOME, label: S.outcome }, { w: COUNT, label: S.count }, { w: SHARE, label: S.share }]}
            />
          )}
        >
          {/* THE TOTAL IS A SPAN, NOT A WIDE FIRST FIELD (M1-73). It is one value stated across
              the whole row, so it declares the two tracks this bay's outcome rows use -- w=26+w=12
              -- and says span=2 so anything comparing first-field edges excludes it by
              declaration instead of by not looking. Measured before: this row's single w=12 field
              rendered 902.5px against the outcome rows' 617.5px, a 384px span across the bay.
              NO FIELD LABEL, for a reason the other rows do not have: the holder edge beside it
              already prints the word `total`, so the label was the same five characters twice on
              one row and a third time in the bay's own head (m1 design review B10). */}
          <Strip edge="grey" edgeLabel={S.total} ariaLabel={S.total}>
            <StripField w={OUTCOME + COUNT + SHARE} span={3} value={fmtInt(payload.stats.total)} />
          </Strip>
          {outcomes.map(([outcome, count]) => (
            <Strip
              key={outcome}
              edge={edgeFor(outcome)}
              edgeLabel={S.state[stateOf(outcome)]}
              ariaLabel={`${S.outcome} ${outcomeText(outcome)}`}
            >
              {/* NO PER-CELL LABELS: the bay prints its column names once, above the rack (B9).
                  Measured on this page before the change: every strip stood 63.8px tall and 42px
                  of that was the value -- 21.8px of every row, a third of it, spent reprinting
                  two words the rack states once. Seven rows here, six in the tail. */}
              <StripField w={OUTCOME} value={outcomeText(outcome)} mono={false} />
              <StripField w={COUNT} value={fmtInt(count)} />
              <StripField w={SHARE} value={shareText(count, payload.stats.total)} />
            </Strip>
          ))}
        </Bay>

        <Bay
          label={S.events}
          count={tail.length}
          empty={NONE}
          fields={(
            <ColumnNames
              columns={[
                { w: WHEN, label: S.when }, { w: HEAD, label: S.head }, { w: EVENT, label: S.outcome },
                { w: CHARS, label: S.chars }, { w: TOOK, label: S.took },
              ]}
            />
          )}
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
                edgeLabel={S.state[stateOf(outcome)]}
                selected={open === key}
                onOpen={() => setOpen(open === key ? null : key)}
                ariaLabel={`${S.openEvent} ${outcomeText(outcome)}`}
              >
                {/* NO PER-CELL LABELS: the bay prints its five column names once (B9). */}
                <StripField w={WHEN} value={timeAgo(row.ts)} />
                <StripField w={HEAD} value={row.head} mono={false} />
                <StripField w={EVENT} value={outcomeText(outcome)} mono={false} />
                <StripField w={CHARS} value={row.chars === undefined ? ABSENT : fmtInt(row.chars)} />
                <StripField w={TOOK} value={row.ms === undefined ? ABSENT : fmtMs(row.ms)} />
              </Strip>
            );
          })}
        </Bay>
      </div>

      {/* THE CLOSED COLUMN HOLDS NOTHING (M3-03), the five-page shape of M1-123. It held a 189px
          "no event opened" empty inside a track that is 0 wide at rest, and that empty hung past the
          page bay by 149-155px at 1280, 1440 and 1600 -- the only page in the console that scrolled
          sideways at a desktop width. The swell has nothing to fill until a strip is opened, so the
          aside stays mounted (the track needs something to transition from), empty, and hidden from
          the landmark list by the same expression that gates its content. */}
      <aside className="myx-cfeed-detail myx-swell" aria-label={S.detail} aria-hidden={opened === null}>
        {opened === null ? null : (
          <>
            <Key className="myx-swell-close" onClick={() => setOpen(null)}>{S.close}</Key>
            <Strip
              edge={edgeFor(opened.outcome ?? 'unknown')}
              edgeLabel={S.state[stateOf(opened.outcome ?? 'unknown')]}
              ariaLabel={S.detail}
            >
              {/* The reader's own clock: this printed the UTC time before (`toISOString`), which is
                  hours off for anyone not in UTC and matched no other time on the page. */}
              <StripField w={13} label={S.when} value={new Date(opened.ts).toTimeString().slice(0, 8)} />
              <StripField w={18} label={S.head} value={opened.head} mono={false} />
              <StripField w={22} label={S.outcome} value={outcomeText(opened.outcome ?? 'unknown')} mono={false} />
              <StripField w={15} label={S.chars} value={opened.chars === undefined ? ABSENT : fmtInt(opened.chars)} />
              <StripField w={11} label={S.took} value={opened.ms === undefined ? ABSENT : fmtMs(opened.ms)} />
              <StripField w={22} label={S.instructions} value={instructionsText(opened.instructions_source)} mono={false} />
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
