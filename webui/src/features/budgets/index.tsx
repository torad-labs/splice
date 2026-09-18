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

/** Parse a typed dollar amount. An empty or unparsable field is "no budget", which is a real state
 *  and not a validation error: clearing a budget is how an operator removes one. */
export function parseUsd(raw: string): number | null {
  const trimmed = raw.trim().replace(/^\$/, '');
  if (trimmed === '') return null;
  const value = Number(trimmed);
  return Number.isFinite(value) && value >= 0 ? value : null;
}

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
                save({ ...budget, daily_usd: parseUsd(typed) });
                setDraft((current) => ({ ...current, [budget.head]: formatUsd(parseUsd(typed)) }));
              }}
            >
              {S.save}
            </button>
            <span className="myx-bud-note">{budgetText(budget)}</span>
            {notes[budget.head] ? <span className="myx-bud-note">{notes[budget.head]}</span> : null}
          </div>
        );
      })}
    </section>
  );
}
