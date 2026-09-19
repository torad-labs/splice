// One turn's waterfall: a stage bar from arrival to finish, the phase rows that make queue wait,
// upstream wait and streaming separate, and the counters beside it.
//
// The bars are drawn from data at runtime inside a ScopeInset (its contract), and the SVG carries
// GEOMETRY ONLY: every label is HTML beside it. That is deliberate - the axis is scaled to the
// widget's width (preserveAspectRatio="none"), and text inside such a viewBox would be stretched
// with it. It is also what keeps the chart readable in grayscale: the rows are named in words.
import { ScopeInset, Strip, StripField } from '@shared/ui';
import { waterfall } from '@entities/perf';
import type { TurnRow } from '@entities/perf';
import { barRows, totalOf } from './model';
import { S } from './strings';
import './waterfall.css';

// The drawer rides with the waterfall: both are a turn's detail, and the logs page opens the same
// drawer for a line that names a turn (FEATURES.md 4.9).
export { CAPTURE_OFF, RequestDrawer } from './request-drawer';

const ROW_H = 18;
const AXIS = 1000;

/** 0 in, 0 out: a counter the row does not carry prints `-`, never a zero it did not report. */
function count(value: number | undefined): string {
  return value === undefined ? '-' : String(value);
}

/** The counters FEATURES.md 4.3 puts beside the bar. */
function counterFields(row: TurnRow): { key: string; label: string; value: string }[] {
  const tools = row.tools_eager === undefined && row.tools_deferred === undefined
    ? '-'
    : `${row.tools_eager ?? 0} / ${(row.tools_eager ?? 0) + (row.tools_deferred ?? 0)}`;
  return [
    { key: 'retries', label: S.retries, value: count(row.retries) },
    { key: 'refreshes', label: S.refreshes, value: count(row.refreshes) },
    { key: 'backoff', label: S.backoff, value: count(row.backoff_ms) },
    { key: 'post_send', label: S.postSend, value: count(row.post_send_retries) },
    { key: 'req_bytes', label: S.requestBytes, value: count(row.req_bytes) },
    { key: 'upstream_bytes', label: S.upstreamBytes, value: count(row.upstream_req_bytes) },
    { key: 'frames', label: S.frames, value: count(row.frames_out) },
    { key: 'tools', label: S.tools, value: tools },
    { key: 'search_rounds', label: S.searchRounds, value: count(row.search_rounds) },
    { key: 'async_io_drops', label: S.dropped, value: count(row.async_io_drops) },
  ];
}

export function Waterfall({ row }: { row: TurnRow }) {
  const stages = waterfall(row);
  const total = totalOf(stages);
  const rows = barRows(stages, total);

  if (rows.length === 0) {
    // A row with no marks is not a turn that took no time: it is a row whose telemetry the daemon
    // did not write, which is what a turn killed before the first mark looks like.
    return <ScopeInset title={S.phases} basis="unavailable">no stage marks on this turn</ScopeInset>;
  }

  return (
    <div className="myx-wf">
      <ScopeInset title={S.phases} basis="measured">
        <svg
          className="myx-wf-svg"
          viewBox={`0 0 ${AXIS} ${rows.length * ROW_H}`}
          height={rows.length * ROW_H}
          preserveAspectRatio="none"
          role="img"
          aria-label={`${S.phases}: ${rows.map((r) => `${r.group} ${r.ms}ms`).join(', ')}`}
        >
          {rows.map((row_, index) => (
            <g key={row_.group}>
              <rect className="myx-wf-track" x={0} y={index * ROW_H + 2} width={AXIS} height={ROW_H - 4} />
              {row_.bars.map((bar) => (
                <rect
                  key={bar.key}
                  className="myx-wf-bar"
                  x={bar.x * AXIS}
                  y={index * ROW_H + 2}
                  width={Math.max(1, bar.w * AXIS)}
                  height={ROW_H - 4}
                />
              ))}
            </g>
          ))}
        </svg>
        <div className="myx-wf-legend">
          {rows.map((row_) => (
            <div className="myx-wf-legend-row" key={row_.group}>
              <span className="myx-wf-legend-name">{S[row_.group]}</span>
              <span className="myx-wf-legend-ms">{row_.ms}ms</span>
            </div>
          ))}
        </div>
      </ScopeInset>

      <Strip
        edge="grey"
        edgeLabel={S.counters}
        ariaLabel={`${S.counters} for ${row.head}`}
      >
        {counterFields(row).map((field) => (
          <StripField key={field.key} w={12} label={field.label} value={field.value} />
        ))}
      </Strip>
    </div>
  );
}
