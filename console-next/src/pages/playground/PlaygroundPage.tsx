import { useEffect, useRef, useState } from 'react';
import type { KeyboardEvent } from 'react';
import { useSearchParams } from 'react-router';
import { isPendingRoute } from '../../api/auth';
import { failureText } from '../../api/client';
import { useModels } from '../../api/models';
import { usePlayground } from '../../api/playground';
import { useHeads, useStatus } from '../../api/queries';
import { fmtInt } from '../../lib/format';
import { colourFromRegistry } from '../../lib/model';
import type { ModelColour } from '../../lib/model';
import { answerOf, canTry, defaultLanes, laneKeys, lanesOf, lanesSearch, nextLane, refuserOf } from '../../lib/playground';
import type { Lane } from '../../lib/playground';
import type { HeadStatus } from '../../types/core';
import type { HeadCatalog } from '../../types/models';
import type { PlaygroundWire } from '../../types/playground';
import { Button, Close, Empty, Fault, Markdown, PageHead, Plus, Select, Window, WindowBar } from '../../ui';
import { ModelSelect } from '../../ui/ModelSelect';
import { G } from './copy';
import './playground.css';

/** One send: every lane on the page when it was made asks its model this prompt once. The id only grows. */
interface Round {
  id: number;
  prompt: string;
}

/** A catalog choice, the command default, or an explicitly entered custom ID. */
function ModelField({ lane, catalog, onChange }: { lane: Lane; catalog: HeadCatalog | undefined; onChange: (model: string | null) => void }) {
  const pinned = catalog?.pinned_model ?? '';
  return <div className="field">
    <span className="eyebrow">{G.model}</span>
    <ModelSelect label={G.model} value={lane.model} models={catalog?.models ?? []} defaultLabel={pinned === '' ? G.anyModel : G.pinnedModel(pinned)} onChange={onChange} />
  </div>;
}

/** What one lane's model answered: its text, how long and how many tokens it took, and the exchange itself on request. A refusal says
 *  who refused and what they said, and an error a stream reported says who stopped; the provider is named by the host it went to. */
export function Reply({ exchange, took }: { exchange: PlaygroundWire; took: number | null }) {
  const { status, body } = exchange.response;
  const answer = answerOf(status, body);
  const refused = status >= 400;
  const who = refuserOf(exchange.request.url);
  const seconds = took === null ? null : (took / 1000).toFixed(1);
  const said = [
    seconds === null ? null : refused ? G.status(status, seconds) : G.answered(status, seconds),
    answer.input === null || answer.output === null ? null : G.tokens(fmtInt(answer.input), fmtInt(answer.output)),
  ].filter((part): part is string => part !== null);
  const alert = refused
    ? (answer.error === null ? G.refusedBare(who, status) : G.refused(who, answer.error))
    : (answer.error === null ? null : G.stopped(who, answer.error));
  return (
    <>
      <p className="pg-said" role="status">{said.map((part) => <span key={part}>{part}</span>)}</p>
      {alert === null ? null : <p className="hint alert" role="alert">{alert}</p>}
      {alert === null && answer.text !== null ? <div className="pg-text"><Markdown>{answer.text}</Markdown></div> : null}
      {alert === null && answer.text === null ? <p className="hint">{G.noText}</p> : null}
      <details className="pg-exchange">
        <summary>{G.exchange}</summary>
        <section aria-label={G.sent}><h4>{G.sent}</h4><pre>{JSON.stringify(exchange.request, null, 2)}</pre></section>
        <section aria-label={G.reply}><h4>{G.reply}</h4><pre>{JSON.stringify(body, null, 2)}</pre></section>
      </details>
    </>
  );
}

interface LaneProps {
  lane: Lane;
  index: number;
  round: Round | null;
  commands: readonly HeadStatus[];
  catalog: HeadCatalog | undefined;
  colour: ModelColour;
  removable: boolean;
  onChange: (next: Lane) => void;
  onRemove: () => void;
}

/** One model's column. It owns its own exchange, so each answer lands when its model finishes and none waits for the slowest. It
 *  asks only for rounds sent after it appeared: a lane added, or changed, after a send waits for the next one. */
function LaneView({ lane, index, round, commands, catalog, colour, removable, onChange, onRemove }: LaneProps) {
  const run = usePlayground();
  const seen = useRef(round?.id ?? 0);
  const [took, setTook] = useState<number | null>(null);
  useEffect(() => {
    if (round === null || round.id === seen.current) return;
    seen.current = round.id;
    const started = performance.now();
    setTook(null);
    run.mutate(
      { head: lane.head, prompt: round.prompt, ...(lane.model === null ? {} : { model: lane.model }) },
      { onSettled: () => setTook(performance.now() - started) },
    );
  }, [round, lane, run]);

  const label = commands.find((command) => command.key === lane.head)?.label ?? lane.head;
  const modelId = lane.model ?? catalog?.pinned_model ?? '';
  const title = modelId === '' ? label : catalog?.models.find(model => model.id === modelId)?.label || modelId;
  const options = commands.map((command) => ({ id: command.key, label: command.label }));
  return (
    <Window as="li" colour={colour} className="pg-lane" aria-label={title}>
      <WindowBar title={title}>
        {removable ? <button type="button" className="icon-btn" aria-label={G.remove(title)} onClick={onRemove}><Close /></button> : null}
      </WindowBar>
      <div className="pg-pick">
        <div className="field">
          <span className="eyebrow">{G.command}</span>
          <Select label={`${G.command} ${index + 1}`} value={lane.head} options={options.some((option) => option.id === lane.head) ? options : [{ id: lane.head, label: lane.head }, ...options]} onChange={(head) => onChange({ head, model: null })} />
        </div>
        <ModelField lane={lane} catalog={catalog} onChange={(model) => onChange({ ...lane, model })} />
      </div>
      <div className="pg-answer">
        {run.isIdle ? <p className="hint">{G.waiting}</p> : null}
        {run.isPending ? <p className="hint" role="status">{G.asking}</p> : null}
        {run.isError ? <p className="hint alert" role="alert">{G.failed} {failureText(run.error)}</p> : null}
        {run.data === undefined ? null : <Reply exchange={run.data} took={took} />}
      </div>
    </Window>
  );
}

/** One prompt to several models, their answers side by side. The lanes live in the address (`?try=claudex:gpt-6-luna&try=claudeor`);
 *  the prompt and the answers live in this page and nowhere else, so nothing is recorded. */
export function PlaygroundPage() {
  const [params, setParams] = useSearchParams();
  const heads = useHeads();
  const models = useModels();
  const status = useStatus();
  const [prompt, setPrompt] = useState('');
  const [round, setRound] = useState<Round | null>(null);
  const [epoch, setEpoch] = useState(0);

  const all = heads.data?.heads ?? [];
  const commands = all.filter((head) => canTry(head.authKind));
  const forwarded = all.filter((head) => !canTry(head.authKind)).map((head) => head.label);
  const asked = lanesOf(params);
  const lanes = asked.length > 0 ? asked : defaultLanes(commands.map((head) => head.key));
  const keys = laneKeys(lanes);
  const catalogs = models.data === undefined || isPendingRoute(models.data) ? [] : models.data.heads;
  const colourOf = colourFromRegistry(status.data);
  const go = (next: readonly Lane[]): void => setParams(lanesSearch(next), { replace: true });
  const added = nextLane(lanes, commands.map((head) => head.key));

  const ready = prompt.trim() !== '' && lanes.length > 0;
  const send = (): void => {
    if (ready) setRound({ id: (round?.id ?? 0) + 1, prompt });
  };
  const clear = (): void => {
    setPrompt('');
    setRound(null);
    setEpoch(epoch + 1);
  };
  const onKey = (event: KeyboardEvent<HTMLTextAreaElement>): void => {
    if (event.key === 'Enter' && (event.metaKey || event.ctrlKey)) {
      event.preventDefault();
      send();
    }
  };

  const head = <PageHead title={G.title} lede={G.lede} />;
  if (heads.isError) return <>{head}<Fault message={failureText(heads.error)} onRetry={() => void heads.refetch()} /></>;
  if (heads.data !== undefined && commands.length === 0) return <>{head}<Empty title={G.none} why={G.noneWhy} /></>;

  return (
    <div className="playground">
      {head}
      <form className="pg-ask" onSubmit={(event) => { event.preventDefault(); send(); }}>
        <label className="field">
          <span className="eyebrow">{G.prompt}</span>
          <textarea className="input" rows={3} placeholder={G.placeholder} value={prompt} spellCheck={false} onChange={(event) => setPrompt(event.currentTarget.value)} onKeyDown={onKey} />
        </label>
        <div className="pg-acts">
          <Button kind="go" type="submit" disabled={!ready}>{G.send}</Button>
          <Button kind="quiet" disabled={prompt === '' && round === null} onClick={clear}>{G.clear}</Button>
          <span className="hint">{G.sendHint}</span>
          <Button className="pg-add" disabled={added === null} onClick={() => added !== null && go([...lanes, added])}><Plus />{G.add}</Button>
        </div>
      </form>
      {forwarded.length === 0 ? null : <p className="hint pg-forwarded">{G.forwarded(forwarded)}</p>}
      <ol className="pg-lanes" aria-label={G.lanes}>
        {lanes.map((lane, index) => (
          <LaneView
            key={`${epoch}:${keys[index] ?? index}`}
            lane={lane}
            index={index}
            round={round}
            commands={commands}
            catalog={catalogs.find((catalog) => catalog.head === lane.head)}
            colour={colourOf(lane.head)}
            removable={lanes.length > 1}
            onChange={(next) => go(lanes.map((one, at) => (at === index ? next : one)))}
            onRemove={() => go(lanes.filter((_, at) => at !== index))}
          />
        ))}
      </ol>
    </div>
  );
}
