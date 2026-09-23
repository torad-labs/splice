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
import type { CaptureState } from '@entities/perf';
import { useControlStatus } from '@entities/control-status';
import { fetchCapture, putCapture, useCapture } from '@entities/perf';
import { Bay, Empty, Figure, HolderEdge } from '@shared/ui';
import { Choice } from '@shared/controls';
import { LogTail } from '@widgets/log-tail';
import { RequestDrawer } from '@widgets/waterfall';
import { S } from './strings';
import './logs.css';

const TAIL_SIZES = [50, 200, 500, 1000];

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
  capture?: CaptureState | null;
  /** A capture read that failed, in the daemon's words. */
  captureError?: string | null;
  /** Writes the tailed head's capture switch. */
  onCaptureSwitch?: (enabled: boolean) => void;
  locked?: boolean;
  error?: string | null;
  /** The fixture's own file name when a fixture fed this board, undefined otherwise: the capture
   *  marker and the sample chrome are the same value, so they cannot disagree. */
  sample?: string | undefined;
  onFilter?: (filter: LogFilter) => void;
  onFollow?: (follow: boolean) => void;
  onHead?: (head: string) => void;
  onTail?: (tail: number) => void;
}

export function LogsBoard({
  payload, filter, follow, appended, reset, tags, levels, head, tail, heads, capture, captureError = null,
  onCaptureSwitch, locked = false, error = null, sample, onFilter, onFollow, onHead, onTail,
}: LogsBoardProps) {
  if (locked) return <Empty text="console locked" source="management key" />;
  // A capture fixture IS the data: a live read that failed behind it must not blank the page.
  if (error !== null && payload === null) return <Empty text="log tail unreadable" source={error} />;

  return (
    <div
      className="myx-lg"
      {...(import.meta.env.DEV && sample !== undefined ? { 'data-sample': sample } : {})}
    >
      <header className="myx-lg-head">
        <h2 className="myx-lg-title">{S.title}</h2>
        {/* THE FOUR CHOICES ARE THE WORLD'S CONTROL (M1-103). These were four native `<select>`s,
            which Choice's own header names as the reason it exists: a select's POPUP is the OS's
            window with the OS's font and scrollbar, and `appearance: none` cannot reach inside it.
            Choice prints the options in flow instead -- a rack of one-field strips under the box,
            with no absolute layer to be clipped or land off-screen -- so this is a STATED BETTER
            shape rather than an equivalent one, and it is the replacement the primitive was written
            for. BEHAVIOUR IS PRESERVED ONE FOR ONE: the same value goes to the same callback, the
            tail is stringified for the box and parsed back through Number, and the two filters keep
            their empty-string-means-all sentinel, mapping to null exactly where they did before.
            Keyboard is the native select's: Enter, Space and the arrows open and move, Home and End
            jump, Escape closes, and a printable character type-aheads. */}
        <Choice
          label="head"
          value={head}
          options={heads.map((entry) => ({ value: entry.key, label: entry.label }))}
          onChange={(next) => onHead?.(next)}
          w={18}
        />
        <Choice
          label={S.tail}
          value={String(tail)}
          options={TAIL_SIZES.map((size) => ({ value: String(size), label: String(size) }))}
          onChange={(next) => onTail?.(Number(next))}
          // 12, not 8 (M3-04): the box prints the value AND its state word, and at 8 the finish
          // review read `2…` where 200 stood
          w={12}
        />
        <Choice
          label={S.tag}
          value={filter.head ?? ''}
          options={[{ value: '', label: S.all }, ...tags.map((tag) => ({ value: tag, label: tag }))]}
          onChange={(next) => onFilter?.({ ...filter, head: next === '' ? null : next })}
          w={16}
        />
        <Choice
          label={S.level}
          value={filter.level ?? ''}
          options={[{ value: '', label: S.all }, ...levels.map((level) => ({ value: level, label: level }))]}
          onChange={(next) => onFilter?.({ ...filter, level: next === '' ? null : (next as LogLevel) })}
          w={10}
        />
        {reset ? <Figure value={1} unit="rotated" basis="measured" /> : null}
        {sample === undefined ? null : <HolderEdge state="grey" label={S.sample} />}
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
        {/* Only the tailed head's capture: another head's read never stands in while this one's is
            in flight. */}
        <RequestDrawer
          capture={capture !== undefined && capture !== null && capture.running.head === head ? capture : null}
          error={captureError}
          onSwitch={onCaptureSwitch}
        />
      </Bay>
    </div>
  );
}

interface Fixture {
  payload: LogsPayload;
}

/** One fixture, as ONE value: the name it was asked for and the bytes that arrived. The capture
 *  marker is set from this and from nothing else, so a name with no module can never leave a
 *  marker behind - a marker that survives a failed import says the opposite of the truth (law 23:
 *  an instrument must be able to distinguish PASSED, FAILED and DID NOT RUN). Exported because a
 *  test pins exactly that, with a name that resolves to no file at all. */
export async function loadFixture(name: string): Promise<{ name: string; payload: Fixture } | null> {
  if (!import.meta.env.DEV) return null;
  const module = await import(/* @vite-ignore */ `./fixtures/${name}.ts`)
    .then((loaded: { fixture?: Fixture }) => loaded)
    .catch(() => null);
  const payload = module === null ? null : module.fixture ?? null;
  return payload === null ? null : { name, payload };
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
  // The head the operator picked, else the FIRST head the daemon's registry reports: never a name
  // written into the console, which 404ed on every install that did not carry it (2026-09-22).
  const [chosen, setChosen] = useState<string | null>(null);
  const heads = registry ?? [];
  const head = chosen ?? heads[0]?.key ?? null;
  const [tail, setTail] = useState(200);
  const [filter, setFilter] = useState<LogFilter>({ head: null, level: null, substring: '' });
  const [follow, setFollow] = useState(true);
  const [cursor, setCursor] = useState<{ tail: Tail | null; appended: number; reset: boolean }>({
    tail: null,
    appended: 0,
    reset: false,
  });
  const [sample, setSample] = useState<{ name: string; payload: Fixture } | null>(null);
  const name = fixtureName();
  const fixture = sample === null ? null : sample.payload;

  useEffect(() => startLogsPolling(5000), []);

  // The slice polls a head of its own until this page names one, and an unknown head 404s: the
  // page's head is set here, on mount as well as on every change.
  useEffect(() => {
    if (head !== null) setLogHead(head);
  }, [head]);

  // The drawer asks about the head this page is tailing. There is no turn to ask about here: a log
  // line names its head and its outcome, not a turn id, and the address scheme that would link the
  // two is an open decision the builders must not invent (surface brief section 7).
  useEffect(() => {
    if (head !== null) void fetchCapture(head);
  }, [head]);

  useEffect(() => {
    if (name === null) {
    // The address no longer asks for this page's fixture, so the marker must GO: a name that is
    // asked for and then dropped is exactly the stale marker this row exists to prevent (measured
    // in a browser on 2026-09-18 - five pages kept one across a hash change, because the early
    // return left the previous state in place; a static render cannot see an effect, so the suite
    // was green while it happened).
      setSample(null);
      return undefined;
    }
    let live = true;
    // The whole value, name and bytes together: a second state for the name would be the stale
    // marker this row exists to prevent.
    void loadFixture(name).then((loaded) => {
      if (live) setSample(loaded);
    });
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


  return (
    <LogsBoard
      payload={filtered}
      filter={filter}
      follow={follow}
      appended={cursor.appended}
      reset={cursor.reset}
      tags={tags}
      levels={levels}
      head={head ?? ''}
      tail={tail}
      heads={heads}
      capture={capture.data}
      captureError={capture.error}
      {...(head === null ? {} : { onCaptureSwitch: (enabled: boolean) => void putCapture(head, enabled) })}
      locked={false}
      error={store.error}
      sample={sample?.name}
      onFilter={setFilter}
      onFollow={setFollow}
      onHead={setChosen}
      onTail={(next) => {
        setTail(next);
        setLogTail(next);
      }}
    />
  );
}
