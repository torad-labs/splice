import { useState } from 'react';
import { failureText } from '../../api/client';
import { isPendingRoute } from '../../api/auth';
import { useTopology, useTopologyEdit } from '../../api/config';
import { standingKeys, standingOf, withStanding } from '../../lib/projects';
import type { StandingEdit } from '../../lib/projects';
import { P } from '../../lib/words-projects';
import { Button } from '../../ui';

function FromFile({ label, path }: { label: string; path: string }) {
  return (
    <div className="field fromfile">
      <span className="eyebrow">{label}</span>
      <span><span className="tag">{P.fromFile}</span> <code>{path}</code></span>
      <span className="hint">{P.fromFileWhy}</span>
    </div>
  );
}

/** The repo's standing prompt and its own compaction rule, written through PUT /api/topology. Both are read at boot, so a save
 *  says what it reaches and that it waits for a restart. A file-backed field is shown, not edited. */
export function Standing({ root, live }: { root: string; live: readonly string[] }) {
  const topology = useTopology();
  const edit = useTopologyEdit();
  const payload = topology.data;
  const held = payload === undefined || isPendingRoute(payload) ? null : standingOf(payload.topology, root);
  const [draft, setDraft] = useState<StandingEdit | null>(null);
  if (topology.isError) return <p className="hint alert" role="alert">{failureText(topology.error)}</p>;
  if (payload === undefined) return null;
  if (isPendingRoute(payload) || held === null) return <p className="hint">{P.unavailable}</p>;
  const now = draft ?? { prompt: held.prompt, compaction: held.compaction };
  const changed = now.prompt !== held.prompt || now.compaction !== held.compaction;
  const findings = edit.data?.findings ?? [];
  return (
    <form
      className="standing"
      onSubmit={(event) => {
        event.preventDefault();
        edit.mutate({ topology: withStanding(payload.topology, root, now), keys: standingKeys(root) }, { onSuccess: () => setDraft(null) });
      }}
    >
      {held.promptFile === null ? (
        <label className="field"><span className="eyebrow">{P.prompt}</span><textarea className="input" spellCheck={false} value={now.prompt} onChange={(event) => setDraft({ ...now, prompt: event.target.value })} /></label>
      ) : <FromFile label={P.prompt} path={held.promptFile} />}
      {held.compactionFile === null ? (
        <label className="field"><span className="eyebrow">{P.rule}</span><textarea className="input" spellCheck={false} value={now.compaction} onChange={(event) => setDraft({ ...now, compaction: event.target.value })} /></label>
      ) : <FromFile label={P.rule} path={held.compactionFile} />}
      <div className="field"><span className="eyebrow">{P.reaches}</span><span>{live.length === 0 ? P.reachesNone : live.join(', ')}</span></div>
      <div className="acts-row">
        <Button kind="go" type="submit" disabled={!changed || edit.isPending}>{edit.isPending ? P.saving : P.save}</Button>
      </div>
      {edit.isSuccess && findings.length === 0 && !changed ? <p className="hint" role="status">{P.saved}</p> : null}
      {findings.map((finding) => <p key={finding.path} className="hint alert" role="alert">{finding.path}: {finding.message}</p>)}
      {edit.isError ? <p className="hint alert" role="alert">{P.failed} {failureText(edit.error)}</p> : null}
    </form>
  );
}
