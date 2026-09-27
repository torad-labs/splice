// The landed turns' filters (V4-345, acceptance Q47): a head, a model, a session, a status and a time,
// each a Choice over what the loaded turns carry. All five stay visible even on a quiet head so a
// reader sees how to narrow the list before a failure or a second session appears.
import type { TurnRow } from '@entities/perf';
import { Choice, Key } from '@shared/controls';
import type { ChoiceOption } from '@shared/controls';
import { filterChoices, isFiltered, NO_FILTER } from './select';
import type { TurnFilter } from './select';
import { S } from './strings';

/** The windows a person narrows the loaded day to while chasing what just happened. */
const WITHIN: readonly { ms: number; label: string }[] = [
  { ms: 15 * 60_000, label: S.last15 },
  { ms: 60 * 60_000, label: S.lastHour },
  { ms: 6 * 60 * 60_000, label: S.last6 },
];

const ALL: ChoiceOption = { value: '', label: S.all };

const chosen = (next: string): string | null => (next === '' ? null : next);

export function TurnFilters({ rows, filter, onFilter, nameOf }: {
  /** Every loaded turn, before the filter: the values to choose among. */
  rows: readonly TurnRow[];
  filter: TurnFilter;
  onFilter: (next: TurnFilter) => void;
  nameOf: (key: string) => string;
}) {
  const choices = filterChoices(rows, filter);
  const values = (list: readonly string[], label: (value: string) => string = (value) => value): ChoiceOption[] =>
    [ALL, ...list.map((value) => ({ value, label: label(value) }))];
  return (
    <div className="myx-tn-filters" role="group" aria-label={S.filters}>
      <Choice label={S.command} value={filter.head ?? ''} options={values(choices.heads, nameOf)} onChange={(next) => onFilter({ ...filter, head: chosen(next) })} w={14} />
      <Choice label={S.model} value={filter.model ?? ''} options={values(choices.models)} onChange={(next) => onFilter({ ...filter, model: chosen(next) })} w={16} />
      <Choice label={S.session} value={filter.session ?? ''} options={values(choices.sessions)} onChange={(next) => onFilter({ ...filter, session: chosen(next) })} w={10} />
      <Choice
        label={S.status}
        value={filter.status ?? ''}
        options={[ALL, { value: 'ok', label: S.ok }, { value: 'failed', label: S.failed }]}
        onChange={(next) => onFilter({ ...filter, status: next === 'ok' || next === 'failed' ? next : null })}
        w={8}
      />
      <Choice
        label={S.time}
        value={filter.within === null ? '' : String(filter.within)}
        options={[{ value: '', label: S.anyTime }, ...WITHIN.map((window) => ({ value: String(window.ms), label: window.label }))]}
        onChange={(next) => onFilter({ ...filter, within: next === '' ? null : Number(next) })}
        w={14}
      />
      {isFiltered(filter) ? <Key onClick={() => onFilter(NO_FILTER)}>{S.clear}</Key> : null}
    </div>
  );
}
