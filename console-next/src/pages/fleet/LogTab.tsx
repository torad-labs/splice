import { useEffect, useRef, useState } from 'react';
import { failureText } from '../../api/client';
import { useLogFollow } from '../../api/logs';
import { NO_FILTER, applyFilter, shownAfter } from '../../lib/logs';
import { daysOptions } from '../../lib/settings';
import type { LogLevel } from '../../types/logs';
import { Button, SearchField, Segmented } from '../../ui';
import { HT } from './copy';

const TAILS = [100, 200, 500, 1000] as const;
type LevelChoice = 'all' | Extract<LogLevel, 'error' | 'warn'>;
const LEVELS: readonly (readonly [LevelChoice, string])[] = [['all', HT.levelAll], ['error', HT.levelError], ['warn', HT.levelWarn]];

const tailOptions = (initial: number | null): (readonly [string, string])[] =>
  daysOptions(TAILS, initial).map((size) => [String(size), String(size)] as const);

/** One plan's log, followed: the newest window at first, then what each read adds, the page keeping the newest 2000 lines. */
export function LogTab({ head, initialTail }: { head: string; initialTail: number | null }) {
  const [tail, setTail] = useState(String(initialTail ?? 200));
  const [level, setLevel] = useState<LevelChoice>('all');
  const [query, setQuery] = useState('');
  const [copied, setCopied] = useState(false);
  const [lines, setLines] = useState<string[]>([]);
  const [restarted, setRestarted] = useState(false);
  const follow = useLogFollow(head, Number(tail));
  const box = useRef<HTMLDivElement>(null);
  // following: the view stays on the newest line until the reader scrolls up, and picks the tail back up when they scroll down to it
  const following = useRef(true);

  useEffect(() => {
    setLines([]);
    setRestarted(false);
  }, [head, tail]);
  useEffect(() => {
    const step = follow.data;
    if (step === undefined) return;
    setLines((shown) => shownAfter(shown, step));
    setRestarted(step.reset);
  }, [follow.data]);
  const shown = applyFilter(lines, { ...NO_FILTER, level: level === 'all' ? null : level, substring: query });
  useEffect(() => {
    const element = box.current;
    if (element !== null && following.current) element.scrollTop = element.scrollHeight;
  }, [shown.length]);

  return (
    <>
      <div className="log-tools">
        <Segmented label={HT.lines} value={tail} options={tailOptions(initialTail)} onChange={setTail} />
        <Segmented label={HT.level} value={level} options={LEVELS} onChange={setLevel} />
        <SearchField value={query} onChange={setQuery} label={HT.filter} hint={HT.filter} />
        <Button small onClick={() => void navigator.clipboard.writeText(shown.join('\n')).then(() => setCopied(true))}>{copied ? HT.copiedLog : HT.copyLog}</Button>
      </div>
      {follow.isError ? <p className="hint alert" role="alert">{failureText(follow.error)}</p> : null}
      {restarted ? <p className="hint">{HT.logRestarted}</p> : null}
      <div
        ref={box}
        className="glass log"
        role="log"
        aria-label={HT.filter}
        onScroll={(event) => {
          const element = event.currentTarget;
          following.current = element.scrollHeight - element.scrollTop - element.clientHeight < 24;
        }}
      >
        {shown.length === 0 ? <p className="dim">{HT.logEmpty}</p> : shown.map((line, at) => (
          // a line is its position in the window: log lines repeat, so the text cannot key it
          <p key={at}>{line}</p>
        ))}
      </div>
      {follow.data === undefined ? null : <p className="hint">{HT.logWhere} {follow.data.tail.path}</p>}
    </>
  );
}
