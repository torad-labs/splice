// The budgets panel: one row per head, mounted by the usage page in its actions slot.
//
// It is a FEATURE rather than a page section because the usage page owns its own layout and this
// row does not; the public component is the whole interface between them. A head with no budget is
// the state every head starts in and prints as such, never as $0.00 — a zero budget would block a
// head on its first turn.
import { useEffect, useState } from 'react';
import { PENDING_BUDGETS, budgetFor, budgetText, putBudgets, startBudgetsPolling, useBudgets } from '@entities/budget';
import type { Budget, BudgetAction } from '@entities/budget';
import { Empty, FieldBox } from '@shared/ui';
import { S } from './strings';
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
  if (amount === '') return { ok: false };
  const value = Number(amount);
  return Number.isFinite(value) && value >= 0 ? { ok: true, value } : { ok: false };
}

/** What the row says under a box that does not hold an amount. */
export const NOT_AN_AMOUNT = 'not a dollar amount; nothing saved';

/** The reverse, for the field box: null prints EMPTY rather than `0`. */
export function formatUsd(value: number | null): string {
  return value === null ? '' : value.toFixed(2);
}

export function BudgetsPanel({ heads }: { heads: readonly string[] }) {
  const budgets = useBudgets((state) => state);
  const [draft, setDraft] = useState<Record<string, string>>({});
  const [notes, setNotes] = useState<Record<string, string>>({});

  useEffect(() => startBudgetsPolling(POLL_MS), []);

  if (budgets.data !== null && 'pending' in budgets.data) {
    return <Empty text="budgets not built" source={`row ${PENDING_BUDGETS}`} />;
  }

  const payload = budgets.data;
  const rows: Budget[] = heads.map((head) => budgetFor(payload, head) ?? { head, daily_usd: null, action: 'warn' });

  const save = (budget: Budget) => {
    const merged = rows.map((row) => (row.head === budget.head ? budget : row));
    setNotes((current) => ({ ...current, [budget.head]: S.saving }));
    void putBudgets(merged).then(
      () => setNotes((current) => ({ ...current, [budget.head]: '' })),
      () => setNotes((current) => ({ ...current, [budget.head]: 'save failed' })),
    );
  };

  return (
    <section className="myx-bud">
      <h2 className="myx-bud-title">{S.title}</h2>
      {rows.map((budget) => {
        const typed = draft[budget.head] ?? formatUsd(budget.daily_usd);
        return (
          <div key={budget.head} className="myx-bud-row">
            <span className="myx-bud-note">{budget.head}</span>
            <FieldBox
              label={S.daily}
              value={typed}
              provenance="state file"
              hot
              onChange={(value) => setDraft((current) => ({ ...current, [budget.head]: value }))}
            />
            {/* The action is a toggle rather than a free field: the daemon's two values are a
                warning and a refusal, and a text box would invite a third spelling. */}
            <button
              type="button"
              className={budget.action === 'block' ? 'myx-bud-btn myx-bud-btn-block' : 'myx-bud-btn'}
              onClick={() => {
                const nextAction: BudgetAction = budget.action === 'warn' ? 'block' : 'warn';
                save({ ...budget, action: nextAction });
              }}
            >
              {budget.action}
            </button>
            <button
              type="button"
              className="myx-bud-btn"
              onClick={() => {
                const parsed = parseUsd(typed);
                if (!parsed.ok) {
                  setNotes((current) => ({ ...current, [budget.head]: NOT_AN_AMOUNT }));
                  return;
                }
                save({ ...budget, daily_usd: parsed.value });
                setDraft((current) => ({ ...current, [budget.head]: formatUsd(parsed.value) }));
              }}
            >
              {S.save}
            </button>
            <span className="myx-bud-note">{budgetText(budget)}</span>
            {notes[budget.head] ? (
              <span className={notes[budget.head] === NOT_AN_AMOUNT ? 'myx-bud-note myx-bud-refused' : 'myx-bud-note'} role="status">
                {notes[budget.head]}
              </span>
            ) : null}
          </div>
        );
      })}
    </section>
  );
}
