// The opened project's own row: GET /api/projects/{id}, read while its detail is open.
//
// The list strip already carries these counts, and the detail reads them again on purpose: the
// detail route is the daemon's answer for THIS root alone (ProjectsRoutes.project), it moves while
// the detail is open, and a root the daemon no longer knows is a 404 whose sentence the operator
// should read rather than a strip that silently stops updating. It also carries what the rack has
// no column for: the day the counts start at, which the daemon sends rather than letting the
// console guess a boundary.
import { useEffect } from 'react';
import { startProjectPolling, useProject } from '@entities/project';
import type { ProjectRow } from '@entities/project';
import { Fault } from '@shared/controls';
import { timeAgo } from '@shared/lib';
import { Strip, StripField } from '@shared/ui';
import type { Basis } from '@shared/ui';
import { S } from './strings';

export interface DetailField {
  key: string;
  label: string;
  w: number;
  value: string;
  basis?: Basis | undefined;
}

/** The UTC day the daemon counted (ProjectsRoutes: "TODAY IS THE UTC DAY"), printed as that day
 *  and that zone, so it never reads as the reader's local midnight. */
export function dayText(dayStart: number): string {
  return `${new Date(dayStart).toISOString().slice(0, 10)} UTC`;
}

/** The detail's two strips, from the row the detail route answered. Pure, so a test reads them. */
export function detailFieldsOf(row: ProjectRow, now = Date.now()): { counts: DetailField[]; today: DetailField[] } {
  return {
    counts: [
      { key: 'live', label: S.liveSessions, w: 13, value: String(row.live_sessions), basis: 'measured' },
      { key: 'teams', label: S.teams, w: 8, value: String(row.teams), basis: 'measured' },
      { key: 'turns', label: S.turnsToday, w: 12, value: String(row.turns_today), basis: 'measured' },
    ],
    today: [
      // Declared rates make a dollar figure an estimate; no rates is the absence of a card, never $0.
      row.cost_today_usd === null
        ? { key: 'cost', label: S.costToday, w: 16, value: S.absent }
        : { key: 'cost', label: S.costToday, w: 16, value: `$${row.cost_today_usd.toFixed(2)}`, basis: 'estimated' },
      { key: 'day', label: S.dayStart, w: 16, value: dayText(row.day_start), basis: 'measured' },
      row.last_activity === null
        ? { key: 'last', label: S.last, w: 12, value: S.absent }
        : { key: 'last', label: S.last, w: 12, value: timeAgo(row.last_activity, now), basis: 'measured' },
    ],
  };
}

function Field({ field }: { field: DetailField }) {
  return (
    <StripField
      w={field.w}
      label={field.label}
      value={field.value}
      {...(field.basis === undefined ? {} : { basis: field.basis })}
    />
  );
}

/**
 * `row` is the fixture seam (CONTRACTS.md section 4): a capture passes the sample row straight in
 * and nothing is read. Otherwise the row is polled while this is mounted, and only a row for THIS
 * id is shown: the previous project's answer never stands in while the new one is in flight.
 */
export function ProjectDetail({ id, row }: { id: string; row?: ProjectRow | undefined }) {
  const state = useProject((s) => s);

  useEffect(() => (row === undefined ? startProjectPolling(id) : undefined), [id, row]);

  const data = row ?? (state.data !== null && state.data.id === id ? state.data : null);
  const error = row === undefined ? state.error : null;
  if (data === null) return error === null ? null : <Fault message={error} />;
  const fields = detailFieldsOf(data);
  return (
    <>
      {error === null ? null : <Fault message={error} />}
      <Strip
        edge={data.live_sessions > 0 ? 'green' : 'grey'}
        edgeLabel={data.live_sessions > 0 ? S.running : S.quiet}
        ariaLabel={`${S.activity} ${data.root}`}
      >
        {fields.counts.map((field) => <Field key={field.key} field={field} />)}
      </Strip>
      <Strip edge="grey" edgeLabel={S.day} ariaLabel={`${S.day} ${data.root}`}>
        {fields.today.map((field) => <Field key={field.key} field={field} />)}
      </Strip>
    </>
  );
}
