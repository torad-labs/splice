// One turn's timing: a lane per part of the turn on one shared axis, so the queue wait, the
// upstream wait and the streaming read as separate bars (FEATURES.md 4.3), and the counters beside
// them.
//
// Each lane is the kit's waterfall with only that part's segments drawn, scaled to the whole turn,
// in the part's grey (@entities/perf STAGE_MARKS). The lane names and spans are HTML beside the
// bars, so the chart reads in grayscale and at any width.
import { STAGE_MARKS, STAGE_NAMES, waterfall } from '@entities/perf';
import type { TurnRow } from '@entities/perf';
import { fmtBytes, fmtMs } from '@shared/lib';
import { Empty, KeyValue, Waterfall } from '@shared/ui';
import { barRows, totalOf } from './model';
import { H, S } from './strings';
import './waterfall.css';

// The drawer rides with the waterfall: both are a turn's detail, and the logs page opens the same
// drawer for the head it tails (FEATURES.md 4.9).
export { CAPTURE_AT_RESTART, CAPTURE_ON, RequestDrawer } from './request-drawer';

/** A counter's figure, or undefined when the row does not carry it: never a zero it did not
 *  report. */
function count(value: number | undefined, format: (n: number) => string = String): string | undefined {
  return value === undefined ? undefined : format(value);
}

/** The counters FEATURES.md 4.3 puts beside the bar, those the row carries. A counter the daemon
 *  did not write for this turn is left out rather than printed as a dash: ten rows, six of them
 *  dashes, spent the panel on what the turn did not say. */
export function counterRows(row: TurnRow): [string, string][] {
  const tools = row.tools_eager === undefined && row.tools_deferred === undefined
    ? undefined
    : `${row.tools_eager ?? 0} / ${(row.tools_eager ?? 0) + (row.tools_deferred ?? 0)}`;
  const rows: [string, string | undefined][] = [
    [S.retries, count(row.retries)],
    [S.refreshes, count(row.refreshes)],
    [S.backoff, count(row.backoff_ms, fmtMs)],
    [S.postSend, count(row.post_send_retries)],
    [S.requestBytes, count(row.req_bytes, fmtBytes)],
    [S.upstreamBytes, count(row.upstream_req_bytes, fmtBytes)],
    [S.frames, count(row.frames_out)],
    [S.tools, tools],
    [S.searchRounds, count(row.search_rounds)],
    [S.dropped, count(row.async_io_drops)],
  ];
  return rows.flatMap(([label, value]) => (value === undefined ? [] : [[label, value] as [string, string]]));
}

export function TurnWaterfall({ row }: { row: TurnRow }) {
  const stages = waterfall(row);
  const total = totalOf(stages);
  const lanes = barRows(stages, total);

  // A row with no marks is not a turn that took no time: it is a row whose telemetry the daemon
  // did not write, which is what a turn killed before the first mark looks like.
  if (lanes.length === 0) return <Empty text={S.noMarks} source={H.noMarks} />;

  return (
    <div className="myx-tw">
      <div className="myx-tw-lanes" role="list" aria-label={S.phases}>
        {lanes.map((lane) => (
          <div key={lane.group} className="myx-tw-lane" role="listitem">
            <span className="myx-tw-name">{STAGE_NAMES[lane.group]}</span>
            <Waterfall
              stages={stages
                .filter((stage) => stage.group === lane.group)
                .map((stage) => ({ key: stage.key, label: stage.label, start: stage.start, end: stage.end, mark: STAGE_MARKS[stage.group] }))}
              scale={total}
              label={`${STAGE_NAMES[lane.group]} ${fmtMs(lane.ms)}`}
            />
            <span className="myx-tw-ms">{fmtMs(lane.ms)}</span>
          </div>
        ))}
      </div>
      {counterRows(row).length === 0 ? null : <KeyValue rows={counterRows(row)} />}
    </div>
  );
}
