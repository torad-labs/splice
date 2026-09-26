// The budgets panel: one row per head, mounted by the usage page.
//
// It is a FEATURE rather than a page section because the usage page owns its own layout and this
// table does not; the public component is the whole interface between them. A head with no budget
// is the state every head starts in and prints as an empty box, never as $0.00: a zero budget would
// block a head on its first turn.
import { useEffect, useState } from 'react';
import { HeadMark, hueClass, useHues } from '@entities/control-status';
import { budgetFor, putBudgets, startBudgetsPolling, useBudgets } from '@entities/budget';
import type { Budget, BudgetAction, BudgetsSlice } from '@entities/budget';
import { Fault, Input, Key } from '@shared/controls';
import { writeNote } from '@shared/lib';
import type { Resource } from '@shared/lib';
import { DataTable, Empty, Section, Segmented } from '@shared/ui';
import type { Column } from '@shared/ui';
import { H, S } from './strings';
import './budgets.css';

const POLL_MS = 30000;

/** A typed dollar amount, read. An EMPTY box is "no budget", a real state: clearing the box is how
 *  an operator removes one. Anything else that is not a non-negative number is a typo, and a typo
 *  must never save: it used to read as "no budget" and a `5$/day` deleted a $5 budget with no word
 *  on screen (splice-lead's walkthrough, B2). */
export type ParsedUsd = { ok: true; value: number | null } | { ok: false };

export function parseUsd(raw: string): ParsedUsd {
  if (raw.trim() === '') return { ok: true, value: null };
  const amount = raw.trim().replace(/^\$/, '').trim();
  // Plain decimal digits only: Number() also reads `0x10` as 16, `0b11` as 3 and `1e3` as 1000, each
  // a typo that would save a budget nobody typed (code review, 2026-09-24).
  if (!/^(\d+(\.\d*)?|\.\d+)$/.test(amount)) return { ok: false };
  return { ok: true, value: Number(amount) };
}

/** The reverse, for the box: null prints EMPTY rather than `0`. */
export function formatUsd(value: number | null): string {
  return value === null ? '' : value.toFixed(2);
}

/** What a row's save cell prints: "Saved", which fits it, and nothing else. A refusal is the
 *  daemon's sentence and prints under the table instead (BudgetRefusals): in the cell it was cut to
 *  "daily_usd 90…" and pushed the row's key out of view (review capture, 2026-09-25). */
export function cellNote(note: string | undefined): string | null {
  return note === S.saved ? S.saved : null;
}

/** Each refused save on its own line under the table, naming its head, in the daemon's words. */
export function BudgetRefusals({ heads, notes }: { heads: readonly string[]; notes: Readonly<Record<string, string>> }) {
  const refused = heads.filter((head) => (notes[head] ?? '') !== '' && notes[head] !== S.saved);
  return (
    <>
      {refused.map((head) => (
        <p key={head} className="myx-bud-refusal" role="status">
          <HeadMark head={head} />
          <span>{notes[head]}</span>
        </p>
      ))}
    </>
  );
}

const ACTIONS: readonly { value: BudgetAction; label: string }[] = [
  { value: 'warn', label: S.warn },
  { value: 'block', label: S.block },
];

/** `state` is the test seam: a static render only ever sees a store's initial state, so a test hands
 *  the read in. The page passes nothing and the panel reads the store. */
export function BudgetsPanel({ heads, state }: { heads: readonly string[]; state?: Resource<BudgetsSlice> }) {
  const stored = useBudgets((held) => held);
  const budgets = state ?? stored;
  const hueOf = useHues();
  const [draft, setDraft] = useState<Record<string, string>>({});
  const [notes, setNotes] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState<string | null>(null);

  useEffect(() => startBudgetsPolling(POLL_MS), []);

  if (budgets.data !== null && 'pending' in budgets.data) {
    return (
      <Section title={S.title}>
        <Empty text={S.unavailable} source={H.unavailable} />
      </Section>
    );
  }

  const payload = budgets.data;
  const rows: Budget[] = heads.map((head) => budgetFor(payload, head) ?? { head, daily_usd: null, action: 'warn' });
  const note = (head: string, text: string) => setNotes((current) => ({ ...current, [head]: text }));

  const save = (budget: Budget) => {
    const merged = rows.map((row) => (row.head === budget.head ? budget : row));
    setBusy(budget.head);
    note(budget.head, '');
    void putBudgets(merged)
      .then((result) => note(budget.head, writeNote(result, { done: S.saved, pending: S.unavailable }).text))
      .finally(() => setBusy(null));
  };

  const typedOf = (budget: Budget): string => draft[budget.head] ?? formatUsd(budget.daily_usd);

  // No column widths: four columns of fixed-size controls, sized by the table to what they hold. Shares
  // of a narrow panel cut one column or another at every split (the review captures of 2026-09-25).
  const columns: Column<Budget>[] = [
    { key: 'head', label: S.head, primary: true, cell: (budget) => <HeadMark head={budget.head} /> },
    {
      key: 'limit',
      label: S.daily,
      cell: (budget) => (
        <span className="myx-bud-usd">
          <span className="myx-bud-dollar" aria-hidden="true">$</span>
          <Input
            label={`${S.daily} ${budget.head}`}
            hideLabel
            numeric
            w={12}
            placeholder={S.noLimit}
            value={typedOf(budget)}
            invalid={notes[budget.head] === H.notAmount}
            onChange={(value) => setDraft((current) => ({ ...current, [budget.head]: value }))}
          />
        </span>
      ),
    },
    {
      key: 'action',
      label: S.action,
      // Two values and a toggle between them, never a free field: the daemon's words are a warning
      // and a refusal, and a text box would invite a third spelling.
      cell: (budget) => (
        <Segmented label={`${S.action} ${budget.head}`} options={ACTIONS} value={budget.action} onChange={(action) => save({ ...budget, action })} />
      ),
    },
    {
      key: 'save',
      label: '',
      align: 'end',
      cell: (budget) => (
        <span className="myx-bud-save">
          {cellNote(notes[budget.head]) === null ? null : <span className="myx-bud-note" role="status">{cellNote(notes[budget.head])}</span>}
          <Key
            busy={busy === budget.head}
            ariaLabel={`${S.save} ${budget.head}`}
            onClick={() => {
              const parsed = parseUsd(typedOf(budget));
              if (!parsed.ok) {
                note(budget.head, H.notAmount);
                return;
              }
              save({ ...budget, daily_usd: parsed.value });
              setDraft((current) => ({ ...current, [budget.head]: formatUsd(parsed.value) }));
            }}
          >
            {S.save}
          </Key>
        </span>
      ),
    },
  ];

  // The row a refusal belongs to is tinted; the refusal itself prints under the table.
  const refused = (budget: Budget): boolean => (notes[budget.head] ?? '') !== '' && cellNote(notes[budget.head]) === null;

  return (
    <Section title={S.title} info={{ text: H.about, label: S.about }} className="myx-bud">
      {/* Either one leaves the list below empty, and neither is "no budgets": a failed read, and a
          file the daemon cannot parse, which runs every head with none (V4-308). A save is the
          recovery the daemon names, so the list stays. */}
      {budgets.error === null ? null : <Fault message={budgets.error} lastRead={budgets.lastUpdated} />}
      {payload?.unreadable == null ? null : <Fault message={payload.unreadable} />}
      <DataTable
        columns={columns}
        rows={rows}
        rowKey={(budget) => budget.head}
        label={S.title}
        rowHue={(budget) => hueClass(hueOf(budget.head))}
        rowTone={(budget) => (refused(budget) ? 'warn' : null)}
      />
      <BudgetRefusals heads={heads} notes={notes} />
    </Section>
  );
}
