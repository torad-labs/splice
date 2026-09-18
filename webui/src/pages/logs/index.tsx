// The logs page: one head's daemon log as strips, filtered, following if asked.
//
// WHO OWNS WHAT: the page owns the head, the tail size, the filter, follow mode and the CURSOR -
// the state that survives a poll. The widget owns the rendering, and takes all of it as props, so
// the whole page can be rendered from a payload in a test (a virtualized list renders nothing
// without a viewport, which is why the strips themselves are their own exported component).
//
// FOLLOWING: the cursor from @entities/logs says which lines are new. Following keeps the last one
// in view; not following keeps the reader's place and prints how many lines arrived while they
// were away. A rotated log REPLACES the view instead of pretending the whole window is new, and
// the header says it restarted.
import { useEffect, useMemo, useState } from 'react';
import { applyFilter, advance, headsPresent, levelsPresent, startLogsPolling, setLogHead, setLogTail, useLogs } from '@entities/logs';
import type { LogFilter, LogLevel, LogTail as Tail, LogsPayload } from '@entities/logs';
import type { CaptureSlice } from '@entities/perf';
import { useControlStatus } from '@entities/control-status';
import { fetchCapture, useCapture } from '@entities/perf';
import { Bay, Empty, Figure, HolderEdge } from '@shared/ui';
import { LogTail } from '@widgets/log-tail';
import { RequestDrawer } from '@widgets/waterfall';
import { S } from './strings';
import './logs.css';

const TAIL_SIZES = [50, 200, 500, 1000];
const FALLBACK_HEADS = [{ key: 'codex', label: 'claudex' }];
/** The tag whose drawer the page opens: the first head in the tail, so the drawer asks about a
 *  head the operator is actually looking at. */
const DEFAULT_DRAWER_TAG = 'claudex';

export interface LogsBoardProps {
  payload: LogsPayload | null;
  filter: LogFilter;
  follow: boolean;
  appended: number;
  reset: boolean;
  tags: string[];
  levels: LogLevel[];
  head: string;
  tail: number;
  heads: { key: string; label: string }[];
  /** The capture state the drawer prints, read for the head this page is tailing. */
  capture?: CaptureSlice | null;
  locked?: boolean;
  error?: string | null;
  sample?: boolean;
  onFilter?: (filter: LogFilter) => void;
  onFollow?: (follow: boolean) => void;
  onHead?: (head: string) => void;
  onTail?: (tail: number) => void;
}

export function LogsBoard({
  payload, filter, follow, appended, reset, tags, levels, head, tail, heads, capture,
  locked = false, error = null, sample = false, onFilter, onFollow, onHead, onTail,
}: LogsBoardProps) {
  if (locked) return <Empty text="console locked" source="management key" />;
  // A capture fixture IS the data: a live read that failed behind it must not blank the page.
  if (error !== null && payload === null) return <Empty text="log tail unreadable" source={error} />;

  return (
    <div className="myx-lg">
      <header className="myx-lg-head">
        <h2 className="myx-lg-title">{S.title}</h2>
        <label className="myx-lg-field">
          <span className="myx-lg-field-label">head</span>
          <select value={head} onChange={(event) => onHead?.(event.target.value)}>
            {heads.map((entry) => (
              <option key={entry.key} value={entry.key}>{entry.label}</option>
            ))}
          </select>
        </label>
        <label className="myx-lg-field">
          <span className="myx-lg-field-label">{S.tail}</span>
          <select value={tail} onChange={(event) => onTail?.(Number(event.target.value))}>
            {TAIL_SIZES.map((size) => (
              <option key={size} value={size}>{size}</option>
            ))}
          </select>
        </label>
        <label className="myx-lg-field">
          <span className="myx-lg-field-label">{S.tag}</span>
          <select value={filter.head ?? ''} onChange={(event) => onFilter?.({ ...filter, head: event.target.value === '' ? null : event.target.value })}>
            <option value="">{S.all}</option>
            {tags.map((tag) => (
              <option key={tag} value={tag}>{tag}</option>
            ))}
          </select>
        </label>
        <label className="myx-lg-field">
          <span className="myx-lg-field-label">{S.level}</span>
          <select
            value={filter.level ?? ''}
            onChange={(event) => onFilter?.({ ...filter, level: event.target.value === '' ? null : (event.target.value as LogLevel) })}
          >
            <option value="">{S.all}</option>
            {levels.map((level) => (
              <option key={level} value={level}>{level}</option>
            ))}
          </select>
        </label>
        {reset ? <Figure value={1} unit="rotated" basis="measured" /> : null}
        {sample ? <HolderEdge state="grey" label={S.sample} /> : null}
      </header>

      <Bay
        label={S.tail}
        {...(payload === null ? {} : { count: payload.lines.length })}
        empty={{ text: 'no tail read yet', source: '/api/logs/{head}' }}
      >
        <LogTail
          payload={payload}
          filter={filter}
          appended={appended}
          reset={reset}
          follow={follow}
          onFilter={onFilter}
          onFollow={onFollow}
        />
      </Bay>

      <Bay label={S.drawer}>
        <RequestDrawer capture={capture ?? null} />
      </Bay>
    </div>
  );
}

interface Fixture {
  payload: LogsPayload;
}

function fixtureName(): string | null {
  if (!import.meta.env.DEV || typeof window === 'undefined') return null;
  const fromSearch = new URLSearchParams(window.location.search).get('fixture');
  if (fromSearch !== null) return fromSearch;
  const at = window.location.hash.indexOf('?');
  return at === -1 ? null : new URLSearchParams(window.location.hash.slice(at)).get('fixture');
}

export default function LogsPage() {
  const registry = useControlStatus((s) => s.data?.registry);
  const store = useLogs((s) => s);
  const capture = useCapture((s) => s);
  const [head, setHead] = useState(DEFAULT_DRAWER_TAG);
  const [tail, setTail] = useState(200);
  const [filter, setFilter] = useState<LogFilter>({ head: null, level: null, substring: '' });
  const [follow, setFollow] = useState(true);
  const [cursor, setCursor] = useState<{ tail: Tail | null; appended: number; reset: boolean }>({
    tail: null,
    appended: 0,
    reset: false,
  });
  const [fixture, setFixture] = useState<Fixture | null>(null);
  const name = fixtureName();

  useEffect(() => startLogsPolling(5000), []);

  // The slice polls a head of its own until this page names one, and an unknown head 404s: the
  // page's head is set here, on mount as well as on every change.
  useEffect(() => {
    setLogHead(head);
  }, [head]);

  // The drawer asks about the head this page is tailing. There is no turn to ask about here: a log
  // line names its head and its outcome, not a turn id, and the address scheme that would link the
  // two is an open decision the builders must not invent (surface brief section 7).
  useEffect(() => {
    void fetchCapture(head);
  }, [head]);

  useEffect(() => {
    if (name === null) return undefined;
    let live = true;
    void import(/* @vite-ignore */ `./fixtures/${name}.ts`)
      .then((module: { fixture?: Fixture }) => {
        if (live) setFixture(module.fixture ?? null);
      })
      .catch(() => undefined);
    return () => {
      live = false;
    };
  }, [name]);

  const payload = fixture?.payload ?? store.data;

  // The cursor is what makes follow mode and the "N new lines" count honest: it says which lines
  // of this payload the reader has already seen.
  useEffect(() => {
    if (payload === null) return;
    setCursor((previous) => {
      const next = advance(previous.tail, payload);
      return { tail: next.tail, appended: next.appended.length, reset: next.reset };
    });
  }, [payload]);

  const tags = useMemo(() => headsPresent(payload?.lines ?? []), [payload]);
  const levels = useMemo(() => levelsPresent(payload?.lines ?? []), [payload]);
  const filtered = useMemo(
    () => (payload === null ? null : { ...payload, lines: applyFilter(payload.lines, filter) }),
    [payload, filter],
  );

  const heads = registry !== undefined && registry.length > 0 ? registry : FALLBACK_HEADS;

  return (
    <LogsBoard
      payload={filtered}
      filter={filter}
      follow={follow}
      appended={cursor.appended}
      reset={cursor.reset}
      tags={tags}
      levels={levels}
      head={head}
      tail={tail}
      heads={heads}
      capture={capture.data}
      locked={false}
      error={store.error}
      sample={fixture !== null}
      onFilter={setFilter}
      onFollow={setFollow}
      onHead={setHead}
      onTail={(next) => {
        setTail(next);
        setLogTail(next);
      }}
    />
  );
}
