// One request, read whole (V4-345, acceptance Q48): what Claude Code sent the model (its instructions,
// messages and tools) and what came back, from the turn's trace records, with what the provider got
// after splice translated it and what the turn cost. It sits in the turns page's detail, beside the
// list, under the turn's timing and tokens.
//
// READ ONCE, WHEN OPENED, NEVER POLLED: a landed turn's records do not change. The read is the trace's
// one-turn read (GET /api/heads/{head}/trace?turn=ID), the only read that serves a body, keyed by the
// turn id the perf row carries (PerfRowMeta.turn), never guessed from a time.
//
// NOTHING BIG REACHES THE DOCUMENT UNTIL ASKED. A request holds the whole conversation, 1.4 MB and 92
// messages on an ordinary claudex turn (2026-09-27): the newest messages show, the earlier ones wait
// behind one key, and every tool call's input, tool result, thinking and schema waits behind its own
// reveal with its size beside it. A text a person wrote shows, and scrolls inside its box.
//
// A TURN WITH NO REQUEST SAYS WHY, AND WHAT CHANGES IT. Capture is off by default, so on most setups
// the first turn a person opens kept nothing: the detail names that state in one line and puts the
// one control that changes it beside it (turn capture on, then restart), not an empty box.
import { useEffect, useState } from 'react';
import { captureView, readKeptTurn } from '@entities/perf';
import type { CaptureState, KeptTurn, TraceRecord, TraceTurnWire } from '@entities/perf';
import { DaemonRestart } from '@features/daemon-restart';
import { Fault, Key } from '@shared/controls';
import { fmtInt, fmtUsd } from '@shared/lib';
import { Badge, Empty, InfoTip, KeyValue, Reveal, Section } from '@shared/ui';
import { readAnswer, readRequest } from './model';
import type { Message, Part, RequestView } from './model';
import { H, S, U } from './strings';
import './request-detail.css';

export { readAnswer, readRequest } from './model';
export type { AnswerView, Message, Part, RequestView, Tool } from './model';

// why: the last exchange and the turns before it, which is what a person opens a request to read;
// the rest of a 92-message conversation waits behind one key
const NEWEST_SHOWN = 6;

const ROLE: Record<string, string> = { user: S.user, assistant: S.assistant };

const size = (text: string): string => `${fmtInt(text.length)} ${U.chars}`;

function PartView({ part }: { part: Part }) {
  switch (part.kind) {
    case 'text':
      return <pre className="myx-rq-text">{part.text}</pre>;
    case 'thinking':
      return (
        <div className="myx-rq-part">
          <span className="myx-rq-line"><Badge tone="neutral" quiet>{S.thinking}</Badge><span className="myx-rq-size">{size(part.text)}</span></span>
          <Reveal label={S.showThinking}><pre className="myx-rq-text">{part.text}</pre></Reveal>
        </div>
      );
    case 'tool-call':
      return (
        <div className="myx-rq-part">
          <span className="myx-rq-line">{S.called}<code className="myx-rq-tool">{part.name}</code><span className="myx-rq-size">{size(part.input)}</span></span>
          <Reveal label={S.input}><pre className="myx-rq-text">{part.input}</pre></Reveal>
        </div>
      );
    case 'tool-result':
      return (
        <div className="myx-rq-part">
          <span className="myx-rq-line">
            {S.resultOf}<code className="myx-rq-tool">{part.name ?? part.id}</code>
            {part.error ? <Badge tone="danger" quiet>{S.failed}</Badge> : null}
            <span className="myx-rq-size">{size(part.text)}</span>
          </span>
          <Reveal label={S.result}><pre className="myx-rq-text">{part.text}</pre></Reveal>
        </div>
      );
    default:
      return (
        <div className="myx-rq-part">
          <span className="myx-rq-line"><code className="myx-rq-tool">{part.type}</code><span className="myx-rq-size">{size(part.json)}</span></span>
          <Reveal label={S.json}><pre className="myx-rq-text">{part.json}</pre></Reveal>
        </div>
      );
  }
}

function Messages({ messages }: { messages: readonly Message[] }) {
  const [all, setAll] = useState(false);
  const hidden = all ? 0 : Math.max(0, messages.length - NEWEST_SHOWN);
  return (
    <div className="myx-rq-messages">
      {hidden === 0 ? null : <Key onClick={() => setAll(true)}>{`${S.earlier} ${fmtInt(hidden)}`}</Key>}
      <ol className="myx-rq-list" aria-label={S.messages} start={hidden + 1}>
        {messages.slice(hidden).map((message, at) => (
          <li className="myx-rq-message" key={hidden + at}>
            <Badge tone="neutral" quiet>{ROLE[message.role] ?? message.role}</Badge>
            {message.parts.map((part, index) => <PartView key={index} part={part} />)}
          </li>
        ))}
      </ol>
    </div>
  );
}

function Request({ view }: { view: RequestView }) {
  return (
    <div className="myx-rq-body">
      <KeyValue rows={view.settings.map(([key, value]) => [key, <code key={key} className="myx-rq-value">{value}</code>])} />
      <h3 className="myx-rq-sub">{S.instructions}</h3>
      {view.instructions.map((text, index) => <pre key={index} className="myx-rq-text">{text}</pre>)}
      <h3 className="myx-rq-sub">{`${S.messages} ${fmtInt(view.messages.length)}`}</h3>
      <Messages messages={view.messages} />
      <h3 className="myx-rq-sub">{`${S.tools} ${fmtInt(view.tools.length)}`}</h3>
      <ul className="myx-rq-list">
        {view.tools.map((tool, index) => (
          <li key={`${tool.name}-${index}`} className="myx-rq-part">
            <span className="myx-rq-line"><code className="myx-rq-tool">{tool.name}</code><span className="myx-rq-size">{size(tool.schema)}</span></span>
            {tool.description === '' ? null : <span className="myx-rq-about">{tool.description}</span>}
            {tool.schema === '' ? null : <Reveal label={S.schema}><pre className="myx-rq-text">{tool.schema}</pre></Reveal>}
          </li>
        ))}
      </ul>
    </div>
  );
}

/** The attempts' bodies as splice sent them and as the provider answered, one pair per attempt. */
function AsSent({ attempts }: { attempts: readonly TraceRecord[] }) {
  return (
    <ol className="myx-rq-list">
      {attempts.map((attempt, index) => (
        <li key={index} className="myx-rq-part">
          <span className="myx-rq-line">
            {`${S.attempt} ${attempt.attempt ?? index + 1}`}
            {attempt.response?.status === undefined ? null : <code className="myx-rq-tool">{String(attempt.response.status)}</code>}
            {attempt.failure === undefined ? null : <Badge tone="danger" quiet>{attempt.failure}</Badge>}
            <span className="myx-rq-size">{size(attempt.request?.body ?? '')}</span>
          </span>
          <Reveal label={S.body}><pre className="myx-rq-text">{attempt.request?.body ?? ''}</pre></Reveal>
          {attempt.response?.text === undefined ? null : <Reveal label={S.reply}><pre className="myx-rq-text">{attempt.response.text}</pre></Reveal>}
        </li>
      ))}
    </ol>
  );
}

/** What the turn cost: the daemon's figure, the absence of a card said as such, never $0. */
function costOf(read: TraceTurnWire) {
  if (read.cost_usd === undefined) return null;
  if (read.cost_usd !== null) return fmtUsd(read.cost_usd);
  return <span className="myx-rq-line">{S.unpriced}<InfoTip text={H.unpriced} label={S.unpriced} /></span>;
}

/** One read turn: its cost, the request, the answer and the attempts, from its records. */
export function RequestRead({ read }: { read: TraceTurnWire }) {
  const ending = read.records.find((record) => record.kind === 'turn');
  const attempts = read.records.filter((record) => record.kind === 'attempt');
  const request = ending?.client?.body === undefined ? null : readRequest(ending.client.body, ending.client.truncated === true);
  const answer = ending?.answer?.body === undefined ? null : readAnswer(ending.answer.body, ending.answer.stream !== false);
  const cost = costOf(read);
  return (
    <div className="myx-rq">
      {cost === null ? null : <KeyValue rows={[[S.cost, cost]]} />}
      <Section title={S.sent} {...(request !== null && 'view' in request ? { meta: `${fmtInt(request.view.messages.length)} ${U.messages}, ${fmtInt(request.view.tools.length)} ${U.tools}` } : {})}>
        {request === null ? <Empty text={S.notKept} source={H.notKept} /> : 'view' in request ? <Request view={request.view} /> : (
          <>
            <Empty text={request.unread === 'cut' ? S.cut : S.unreadable} source={request.unread === 'cut' ? H.cut : H.unreadable} />
            <Reveal label={S.body}><pre className="myx-rq-text">{request.text}</pre></Reveal>
          </>
        )}
        {ending?.client?.headers === undefined ? null : (
          <Reveal label={S.requestHeaders}>
            <pre className="myx-rq-text">{JSON.stringify(ending.client.headers, null, 2)}</pre>
          </Reveal>
        )}
        {ending?.client?.body === undefined ? null : (
          <Reveal label={`${S.rawRequest} · ${size(ending.client.body)}`}>
            <pre className="myx-rq-text">{ending.client.body}</pre>
          </Reveal>
        )}
      </Section>
      {answer === null ? null : (
        <Section title={S.answer} {...(answer.stopReason === null ? {} : { meta: `${S.stopped}: ${answer.stopReason}` })}>
          {answer.error === null ? null : <Fault message={answer.error} />}
          {answer.parts.map((part, index) => <PartView key={index} part={part} />)}
          {ending?.answer?.body === undefined ? null : (
            <Reveal label={`${S.rawAnswer} · ${size(ending.answer.body)}`}>
              <pre className="myx-rq-text">{ending.answer.body}</pre>
            </Reveal>
          )}
        </Section>
      )}
      {attempts.length === 0 ? null : (
        <Section title={S.asSent} count={attempts.length} info={{ text: H.asSent, label: S.asSent }}>
          <AsSent attempts={attempts} />
        </Section>
      )}
    </div>
  );
}

/** Why a turn kept no request, as capture stands now for its head: one line and the one thing that
 *  changes it. [capture] is null while the head's capture has not been read. */
function NotKeptLine({ capture, onSwitch }: { capture: CaptureState | null; onSwitch: ((enabled: boolean) => void) | undefined }) {
  if (capture === null) return <Empty text={S.notKept} source={H.notKept} />;
  const view = captureView(capture);
  if (view.awaitingRestart) return <Empty text={S.atRestart} source={H.atRestart} action={<DaemonRestart />} />;
  if (!view.running) {
    return (
      <Empty
        text={S.captureOff}
        source={H.captureOff}
        action={<Key onClick={() => onSwitch?.(true)} disabled={onSwitch === undefined || capture.writing} busy={capture.writing}>{S.turnOn}</Key>}
      />
    );
  }
  return <Empty text={S.notKept} source={H.notKept} />;
}

/** The request section of a turn whose perf row names no trace turn: nothing was kept for it. */
export function RequestNotKept({ capture, onSwitch }: {
  capture: CaptureState | null;
  /** Writes the head's capture. Absent, the key is shown and cannot be pressed (a fixture board). */
  onSwitch?: ((enabled: boolean) => void) | undefined;
}) {
  return (
    <Section title={S.sent}>
      <NotKeptLine capture={capture} onSwitch={onSwitch} />
    </Section>
  );
}

/** A read as the detail holds it: the turn, the trace's answer that it no longer holds it, or a fault
 *  in the daemon's words. */
type Fetched = KeptTurn | { fault: string };

/**
 * The request of [turn] on [head], read when this mounts. `read` is the fixture and test seam
 * (CONTRACTS.md section 4): handed in, nothing is read. The page keys this by head and turn, so one
 * turn's read never shows under another's.
 */
export function RequestDetail({ head, turn, read: handed }: { head: string; turn: string; read?: KeptTurn }) {
  const [fetched, setFetched] = useState<Fetched | null>(handed ?? null);
  useEffect(() => {
    if (handed !== undefined) return undefined;
    let live = true;
    readKeptTurn(head, turn).then(
      (value) => { if (live) setFetched(value); },
      (err: unknown) => { if (live) setFetched({ fault: err instanceof Error ? err.message : String(err) }); },
    );
    return () => { live = false; };
  }, [head, turn, handed]);
  if (fetched === null) return <Section title={S.sent}><Empty text={S.loading} /></Section>;
  if ('gone' in fetched) return <Section title={S.sent}><Empty text={S.gone} source={H.gone} /></Section>;
  if ('fault' in fetched) return <Section title={S.sent}><Fault message={fetched.fault} /></Section>;
  return <RequestRead read={fetched.read} />;
}
