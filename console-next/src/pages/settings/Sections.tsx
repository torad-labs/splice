import { useState } from 'react';
import { Link } from 'react-router';
import { failureText } from '../../api/client';
import { useKeyStore, useDeleteKey, usePutKey } from '../../api/auth';
import { useHealth } from '../../api/queries';
import { useMcp, useTopology, useTopologyEdit } from '../../api/config';
import { useDoctor } from '../../api/doctor';
import { isPendingRoute } from '../../api/auth';
import { collapseChecks, checkFinding, wantsAttention } from '../../lib/doctor';
import { timeAgo } from '../../lib/format';
import { doctorFixOf } from '../../lib/needs';
import { serverRows } from '../../lib/mcp';
import { Y } from '../../lib/words-playground';
import {
  ACTIVITY_DAYS, REASONING_CHOICES, TRACE_DAYS, daysOptions, excludedOf, folderOf, gitRootsOf, gitRootsValue, healthOf, numberOf, textOf,
  toolState, withMcpHosting, withServerExcluded,
} from '../../lib/settings';
import type { Saved } from './Row';
import { setShowKeys, useShowKeys } from '../../lib/show-keys';
import { setThemeChoice, useThemeChoice } from '../../lib/theme';
import type { ThemeChoice } from '../../lib/theme';
import type { ConfigPayload } from '../../types/core';
import { Button, Close, Folder, Plus, Prompt, Segmented, Select, Slider, State, Stepper, Switch } from '../../ui';
import { NeedFix } from '../needs/NeedFix';
import { AllSettings } from './AllSettings';
import { T } from './copy';
import { ClaudeHead } from './ClaudeHead';
import { ConfigFile } from './ConfigFile';
import { Compaction } from './Compaction';
import { Kept } from './Kept';
import { PlanInstructions } from './PlanInstructions';
import { CheckMembers } from './CheckMembers';
import { Row, SaveNote, useSetting } from './Row';
import { Upgrade } from './Upgrade';
import { Thinking } from './Thinking';

const THEME: readonly (readonly [ThemeChoice, string])[] = [['day', T.day], ['night', T.night], ['system', T.system]];

export function General({ config }: { config: ConfigPayload }) {
  const choice = useThemeChoice();
  const [copied, setCopied] = useState(false);
  const warn = useSetting('usageWarnPct');
  const debug = useSetting('debug');
  const pct = numberOf(config.effective['usageWarnPct']) ?? 80;
  return (
    <>
      <Row title={T.appearance} why={T.appearanceWhy} control={<Segmented label={T.appearance} value={choice} options={THEME} onChange={setThemeChoice} />} />
      <Row
        title={T.openAt}
        why={T.openAtWhy}
        control={
          <>
            <span className="folder code">{location.host}</span>
            <Button small onClick={() => void navigator.clipboard.writeText(location.host).then(() => setCopied(true))}>{copied ? T.copied : T.copy}</Button>
          </>
        }
      />
      <Row
        title={T.warn}
        why={T.warnWhy}
        settingKey="usageWarnPct"
        control={<Slider label={T.warn} value={Math.min(100, Math.max(50, pct))} min={50} max={100} unit="%" onCommit={(next) => warn.save(next)} />}
        note={<SaveNote keys={['usageWarnPct']} saved={warn.saved} />}
      />
      <Row
        title={T.debug}
        why={T.debugWhy}
        settingKey="debug"
        control={<Switch label={T.debug} checked={config.effective['debug'] === true} onChange={(next) => debug.save(next)} />}
        note={<SaveNote keys={['debug']} saved={debug.saved} />}
      />
    </>
  );
}

export function Conversation({ config }: { config: ConfigPayload }) {
  const inflight = useSetting('maxInflight');
  const reasoning = useSetting('showReasoning');
  return (
    <>
      <Thinking />
      <Row
        title={T.inflight}
        why={T.inflightWhy}
        settingKey="maxInflight"
        control={<Stepper label={T.inflight} value={numberOf(config.effective['maxInflight']) ?? 0} min={0} max={256} onChange={(next) => inflight.save(next)} />}
        note={<SaveNote keys={['maxInflight']} saved={inflight.saved} />}
      />
      <Row
        title={T.reasoning}
        why={T.reasoningWhy}
        settingKey="showReasoning"
        control={<Select label={T.reasoning} value={textOf(config.effective['showReasoning']) || 'text'} options={REASONING_CHOICES} onChange={(next) => reasoning.save(next)} />}
        note={<SaveNote keys={['showReasoning']} saved={reasoning.saved} />}
      />
      <PlanInstructions />
      <Compaction />
    </>
  );
}

function DaysRow({ title, why, settingKey, list, config }: { title: string; why: string; settingKey: string; list: readonly number[]; config: ConfigPayload }) {
  const setting = useSetting(settingKey);
  const current = numberOf(config.effective[settingKey]);
  const options = daysOptions(list, current).map((days) => ({ id: String(days), label: T.days(days) }));
  return (
    <Row
      title={title}
      why={why}
      settingKey={settingKey}
      control={<Select label={title} value={String(current ?? '')} options={options} onChange={(next) => setting.save(Number(next))} />}
      note={<SaveNote keys={[settingKey]} saved={setting.saved} />}
    />
  );
}

function GitFolders({ config }: { config: ConfigPayload }) {
  const setting = useSetting('statuslineGitRoots');
  const roots = gitRootsOf(config.effective['statuslineGitRoots']);
  return (
    <Row
      title={T.git}
      why={T.gitWhy}
      settingKey="statuslineGitRoots"
      control={
        <span className="folders">
          {roots.map((root) => (
            <span key={root} className="folder" title={root}>
              <Folder />
              {root.split('/').filter(Boolean).at(-1) ?? root}
              <button type="button" className="x" aria-label={T.removeFolder(root)} onClick={() => setting.save(gitRootsValue(roots.filter((entry) => entry !== root)))}><Close /></button>
            </span>
          ))}
          <Prompt
            trigger={<Button small><Plus />{T.addFolder}</Button>}
            title={T.folderAsk}
            why={T.folderWhy}
            field={T.folderField}
            submit={T.save}
            cancel={T.cancel}
            onSubmit={async (text) => {
              const path = folderOf(text);
              if (path === null) throw new Error(T.folderInvalid);
              if (!roots.includes(path)) setting.save(gitRootsValue([...roots, path]));
            }}
          />
        </span>
      }
      note={<SaveNote keys={['statuslineGitRoots']} saved={setting.saved} />}
    />
  );
}

/** The OpenRouter key: whether splice's store holds it, and a way to replace it. The value goes one way and is never shown. */
function OpenRouterKey() {
  const store = useKeyStore();
  const put = usePutKey();
  const remove = useDeleteKey();
  const [saved, setSaved] = useState<Saved>(null);
  const state = store.data?.keys.find((key) => key.name === 'OPENROUTER_API_KEY');
  const stored = state?.stored === true;
  const shadowed = stored && (state?.heads.some((head) => head.source === 'environment' || head.source === 'file') ?? false);
  return (
    <Row
      title={T.openrouter}
      why={T.openrouterWhy}
      settingKey="OPENROUTER_API_KEY"
      control={
        <span className="secret">
          <span className={`mask${stored ? '' : ' unset'}`}>{stored ? '••••••••••••' : T.keyNotSet}</span>
          <Prompt
            trigger={<Button small>{stored ? T.keyReplace : T.keySetNew}</Button>}
            title={T.keyAsk}
            why={T.keyWhy}
            field={T.keyField}
            submit={T.save}
            cancel={T.cancel}
            secret
            onSubmit={async (text) => {
              await put.mutateAsync({ name: 'OPENROUTER_API_KEY', value: text.trim() });
              setSaved({ kind: 'saved' });
            }}
          />
          {stored ? <Button small kind="danger" disabled={remove.isPending} onClick={() => remove.mutate('OPENROUTER_API_KEY', { onSuccess: () => setSaved(null), onError: (err) => setSaved({ kind: 'failed', message: failureText(err) }) })}>{T.keyRemove}</Button> : null}
        </span>
      }
      note={
        <>
          <SaveNote keys={[]} saved={saved} />
          {shadowed ? <span className="tip">{T.keyRead}</span> : null}
          {store.isError ? <span className="tip bad" role="alert">{failureText(store.error)}</span> : null}
        </>
      }
    />
  );
}

export function Storage({ config }: { config: ConfigPayload }) {
  return (
    <>
      <DaysRow title={T.history} why={T.historyWhy} settingKey="activityRetentionDays" list={ACTIVITY_DAYS} config={config} />
      <DaysRow title={T.traces} why={T.tracesWhy} settingKey="traceRetentionDays" list={TRACE_DAYS} config={config} />
      <GitFolders config={config} />
      <OpenRouterKey />
      <Kept config={config} />
    </>
  );
}

function SharedTools() {
  const mcp = useMcp();
  const topology = useTopology();
  const edit = useTopologyEdit();
  const [saved, setSaved] = useState<Saved>(null);
  const write = (next: Record<string, unknown>, keys: readonly string[]): void =>
    edit.mutate({ topology: next, keys }, {
      onSuccess: (result) => setSaved(result.ok ? { kind: 'saved' } : { kind: 'rejected', reason: (result.findings ?? []).map((finding) => `${finding.path}: ${finding.message}`).join('; ') }),
      onError: (err) => setSaved({ kind: 'failed', message: failureText(err) }),
    });
  if (mcp.isError) return <p className="hint alert row-note" role="alert">{failureText(mcp.error)}</p>;
  if (topology.data === undefined || mcp.data === undefined) return <p className="hint row-note">{T.reading}</p>;
  if (isPendingRoute(topology.data)) return <p className="hint row-note">{T.toolsUnserved}</p>;
  const file = topology.data.topology;
  const excluded = excludedOf(file);
  return (
    <>
      <Row
        title={T.sharing}
        why={T.sharingWhy}
        settingKey="daemon.mcp_hosting"
        control={<Switch label={T.sharing} checked={mcp.data.hosting} disabled={edit.isPending} onChange={(next) => write(withMcpHosting(file, next), ['daemon.mcp_hosting'])} />}
        note={<SaveNote keys={['daemon.mcp_hosting', 'daemon.mcp_hosting_exclude']} saved={saved} />}
      />
      {!mcp.data.hosting ? <p className="hint row-note">{T.toolsOff}</p> : null}
      {serverRows(mcp.data).length === 0 ? <p className="hint row-note">{T.noTools}</p> : null}
      {serverRows(mcp.data).map(({ name, server }) => {
        const state = toolState(server, excluded.includes(name));
        return (
          <Row
            key={name}
            title={name}
            why={state.why ?? (state.word === 'Running' ? T.toolRunning : state.word === 'Not started' ? T.toolIdle : T.toolExcluded)}
            settingKey="daemon.mcp_hosting_exclude"
            control={
              <>
                <State tone={state.tone}>{state.word}</State>
                <Switch label={name} checked={state.shared} disabled={edit.isPending || !mcp.data.hosting || (!server.eligible && !excluded.includes(name))} onChange={(next) => write(withServerExcluded(file, name, !next), ['daemon.mcp_hosting_exclude'])} />
              </>
            }
          />
        );
      })}
    </>
  );
}

function Checks() {
  const doctor = useDoctor();
  const health = useHealth();
  const data = doctor.data;
  if (doctor.isError) return <p className="hint alert row-note" role="alert">{failureText(doctor.error)}</p>;
  if (data === undefined) return <p className="hint row-note">{T.reading}</p>;
  if (isPendingRoute(data)) return <p className="hint row-note">{T.healthUnserved}</p>;
  const overall = healthOf(data.checks);
  const rows = collapseChecks(data.checks.filter((check) => wantsAttention(check.status)));
  const at = Date.parse(data.generated_at);
  return (
    <>
      <Row
        title={T.healthTitle}
        why={T.healthChecked(Number.isNaN(at) ? 'just now' : timeAgo(at))}
        control={
          <>
            <State tone={overall.tone}>{overall.word}</State>
            <Button small disabled={doctor.isFetching} onClick={() => void Promise.all([doctor.refetch(), health.refetch()])}>{doctor.isFetching ? T.rechecking : T.recheck}</Button>
          </>
        }
      />
      {rows.map((row) => {
        const first = row.members[0];
        return (
          <Row
            key={row.key}
            title={row.label}
            why={first === undefined ? '' : checkFinding(first)}
            note={<CheckMembers row={row} />}
            control={
              <>
                <State tone={row.status === 'fail' ? 'stuck' : 'wait'}>{row.status === 'fail' ? T.needsYou : T.worth}</State>
                <NeedFix fix={doctorFixOf(row.fix, row.fixId, row.fixKind)} tone="quiet" />
              </>
            }
          />
        );
      })}
    </>
  );
}

export const Tools = () => (
  <>
    <SharedTools />
    <ClaudeHead />
  </>
);

export const Health = () => (
  <>
    <Checks />
    <Upgrade />
    <Row title={Y.title} why={Y.why} control={<Link className="btn" to="/playground">{Y.open}</Link>} />
  </>
);

export function Advanced() {
  const showKeys = useShowKeys();
  const [open, setOpen] = useState(false);
  return (
    <>
      <Row title={T.showKeys} why={T.showKeysWhy} control={<Switch label={T.showKeys} checked={showKeys} onChange={setShowKeys} />} />
      <Row
        title={T.everyOther}
        why={T.everyOtherWhy}
        control={<Button small aria-expanded={open} onClick={() => setOpen(!open)}>{open ? T.closeList : T.openList}</Button>}
      />
      {open ? <AllSettings /> : null}
      <ConfigFile />
    </>
  );
}
