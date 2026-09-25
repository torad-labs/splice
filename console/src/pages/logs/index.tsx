// The logs page: the stream surface (docs/design/DESIGN.md section 7). A filter rail on the left
// (the head, the tail length, the tag, the level, a search) and one head's daemon log beside it,
// following if asked.
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
import { useEffect, useMemo, useRef, useState } from 'react';
import { useLocation } from 'react-router';
import { applyFilter, advance, headsPresent, levelsPresent, startLogsPolling, setLogHead, setLogTail, useLogs } from '@entities/logs';
import type { LogFilter, LogLevel, LogTail as Tail, LogsPayload } from '@entities/logs';
import type { CaptureState } from '@entities/perf';
import { useControlStatus } from '@entities/control-status';
import { fetchCapture, putCapture, useCapture } from '@entities/perf';
import { HeadMark } from '@entities/control-status';
import { cx } from '@shared/lib';
import { Badge, Empty, PageHeader, Section, Segmented } from '@shared/ui';
import { Choice, Fault, Input } from '@shared/controls';
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
  /** When the lines on screen were read, which the fault prints as stale while `error` stands. */
  lastRead?: number | null;
  /** The fixture's own file name when a fixture fed this board, undefined otherwise: the capture
   *  marker and the sample chrome are the same value, so they cannot disagree. */
  sample?: string | undefined;
  onFilter?: (filter: LogFilter) => void;
  onFollow?: (follow: boolean) => void;
  onHead?: (head: string) => void;
  onTail?: (tail: number) => void;
}

/** The paused reader's count of lines that arrived since they paused. Following, or on the first
 *  read, nothing is unseen. A RESET STARTS IT OVER: `appended` is then the whole new window, and
 *  adding it read a rotation as 200 new lines, which advance() exists to never say; the `rotated`
 *  edge says what happened instead. */
export function unseenAfter(prior: number, next: { appended: readonly string[]; reset: boolean }, current: boolean): number {
  if (current || next.reset) return 0;
  return prior + next.appended.length;
}

/** The values a filter box offers: those the tail holds, plus the chosen one when it holds it no
 *  longer. */
export function kept<T extends string>(present: readonly T[], chosen: T | null): T[] {
  return chosen === null || present.includes(chosen) ? [...present] : [...present, chosen];
}

export function LogsBoard({
  payload, filter, follow, appended, reset, tags, levels, head, tail, heads, capture, captureError = null,
  onCaptureSwitch, locked = false, error = null, lastRead = null, sample, onFilter, onFollow, onHead, onTail,
}: LogsBoardProps) {
  if (locked) return <Empty text="console locked" source="management key" />;
  // A capture fixture IS the data: a live read that failed behind it must not blank the page.
  if (error !== null && payload === null) return <Empty text="log unreadable" source={error} />;

  return (
    <div
      className="myx-lg"
      {...(import.meta.env.DEV && sample !== undefined ? { 'data-sample': sample } : {})}
    >
      <PageHeader
        title={S.title}
        actions={(
          <>
            {reset ? <Badge tone="warn">{S.rotated}</Badge> : null}
            {sample === undefined ? null : <Badge tone="neutral">{S.sample}</Badge>}
          </>
        )}
      />

      {/* A tail read that fails after one landed keeps the lines it had, and says both things: it
          used to keep them silently, so a dead daemon's last lines read as a quiet live one. */}
      {error === null ? null : <Fault message={error} lastRead={lastRead} />}

      <div className="myx-lg-body">
        {/* THE FILTER RAIL (Vercel's logs, DESIGN.md section 7): every choice the stream answers
            to, on the left, so the stream keeps the width. */}
        <aside className="myx-lg-rail" aria-label={S.filters}>
          <div className="myx-lg-group" role="group" aria-label={S.head}>
            <p className="myx-lg-label">{S.head}</p>
            {heads.map((entry) => (
              <button
                key={entry.key}
                type="button"
                className={cx('myx-lg-head', entry.key === head && 'myx-lg-head-on')}
                aria-pressed={entry.key === head}
                onClick={() => onHead?.(entry.key)}
              >
                <HeadMark head={entry.key}>{entry.label}</HeadMark>
              </button>
            ))}
          </div>

          <div className="myx-lg-group">
            <p className="myx-lg-label">{S.tail}</p>
            <Segmented
              label={S.tail}
              options={TAIL_SIZES.map((size) => ({ value: String(size), label: String(size) }))}
              value={String(tail)}
              onChange={(next) => onTail?.(Number(next))}
            />
          </div>

          <div className="myx-lg-group">
            <Input
              label={S.search}
              value={filter.substring}
              onChange={(next) => onFilter?.({ ...filter, substring: next })}
              w={20}
            />
          </div>

          {/* A filter prints only when it has something to choose: a head's own log carries one tag
              (its own), and the daemon marks a level on few lines or none. A filter already CHOSEN
              keeps its box, and its value stays an option, even once the lines that offered it
              have scrolled out of the tail: the filter still applies, and a hidden box left no way
              to clear it. */}
          {tags.length > 1 || filter.head !== null ? (
            <Choice
              label={S.tag}
              value={filter.head ?? ''}
              options={[{ value: '', label: S.all }, ...kept(tags, filter.head).map((tag) => ({ value: tag, label: tag }))]}
              onChange={(next) => onFilter?.({ ...filter, head: next === '' ? null : next })}
              w={16}
            />
          ) : null}
          {levels.length > 0 || filter.level !== null ? (
            <Choice
              label={S.level}
              value={filter.level ?? ''}
              options={[{ value: '', label: S.all }, ...kept(levels, filter.level).map((level) => ({ value: level, label: level }))]}
              onChange={(next) => onFilter?.({ ...filter, level: next === '' ? null : (next as LogLevel) })}
              w={10}
            />
          ) : null}
        </aside>

        <div className="myx-lg-main">
          <LogTail
            payload={payload}
            appended={appended}
            reset={reset}
            follow={follow}
            tagged={tags.length > 1}
            onFollow={onFollow}
          />

          <Section title={S.drawer}>
            {/* Only the tailed head's capture: another head's read never stands in while this one's
                is in flight. */}
            <RequestDrawer
              capture={capture !== undefined && capture !== null && capture.running.head === head ? capture : null}
              error={captureError}
              onSwitch={onCaptureSwitch}
            />
          </Section>
        </div>
      </div>
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
  // The head the operator picked, or the one the address asks for (`#/logs?head=claudex`, what the
  // doctor's `open log` links to), else the FIRST head the daemon's registry reports: never a name
  // written into the console, which 404ed on every install that did not carry it (2026-09-22). An
  // asked-for head the registry does not list falls back rather than 404ing.
  const { search } = useLocation();
  const [chosen, setChosen] = useState<string | null>(() => new URLSearchParams(search).get('head'));
  const heads = registry ?? [];
  const head = (heads.some((entry) => entry.key === chosen) ? chosen : null) ?? heads[0]?.key ?? null;
  const [tail, setTail] = useState(200);
  const [filter, setFilter] = useState<LogFilter>({ head: null, level: null, substring: '' });
  const [follow, setFollow] = useState(true);
  // Read inside the payload effect, which must not re-run when follow flips.
  const following = useRef(follow);
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
  // of this payload the reader has already seen. The count is what arrived SINCE the reader paused,
  // summed across polls: it held one poll's arrivals before, so it read 0 five seconds after a burst,
  // and it counted the whole first read as new.
  useEffect(() => {
    if (payload === null) return;
    setCursor((previous) => {
      const next = advance(previous.tail, payload);
      const unseen = unseenAfter(previous.appended, next, following.current || previous.tail === null);
      return { tail: next.tail, appended: unseen, reset: next.reset };
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
      error={fixture === null ? store.error : null}
      lastRead={store.lastUpdated}
      sample={sample?.name}
      onFilter={setFilter}
      onFollow={(next) => {
        following.current = next;
        setFollow(next);
        if (next) setCursor((previous) => ({ ...previous, appended: 0 }));
      }}
      onHead={(next) => {
        // A tag or level picked on another head's log would filter this one while its box is
        // hidden (a box prints only when it has a choice), so the filters start over.
        setChosen(next);
        setFilter((previous) => ({ ...previous, head: null, level: null }));
      }}
      onTail={(next) => {
        setTail(next);
        setLogTail(next);
      }}
    />
  );
}
