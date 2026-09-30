import { useState } from 'react';
import { failureText } from '../../api/client';
import { isPendingRoute } from '../../api/auth';
import { useTopology, useTopologyEdit } from '../../api/config';
import { validateTopology } from '../../lib/topology';
import { changedPaths, coerce, groupOf, headOverrideOf, parseList, setAtPath, toToml, topologyTables, valueAtPath } from '../../lib/topology-edit';
import type { TopologyField, TopologyTable } from '../../lib/topology-edit';
import { OnRequest } from '../shared/OnRequest';
import { CF } from '../../lib/words-config-file';
import { Button, Select, Switch, TextInput } from '../../ui';
import { Row } from './Row';

const shown = (value: unknown): string => (value === undefined ? CF.unset : typeof value === 'string' ? value : JSON.stringify(value));

function FieldControl({ field, onChange }: { field: TopologyField; onChange: (value: unknown) => void }) {
  const { value } = field;
  if (field.kind === 'flag') return <Switch label={field.key} checked={value === true} onChange={onChange} />;
  if (field.kind === 'choice') return <Select label={field.key} value={String(value)} options={(field.choices ?? []).map((choice) => ({ id: choice, label: choice }))} onChange={onChange} />;
  if (field.kind === 'list') return <TextInput label={field.key} value={(value as readonly (string | number)[]).join(', ')} onCommit={(raw) => onChange(parseList(raw, value as readonly (string | number)[]))} />;
  return <TextInput label={field.key} value={String(value)} onCommit={(raw) => onChange(coerce(raw, value as string | number))} />;
}

function TableView({ table, group, onChange }: { table: TopologyTable; group: string; onChange: (path: string, value: unknown) => void }) {
  const title = table.path === group ? '' : table.path.slice(group.length + 1);
  return (
    <section className="cf-table" aria-label={table.path === '' ? CF.topLevel : table.path}>
      {title === '' ? null : <h4>{title}</h4>}
      {table.fields.map((field) => (
        <div key={field.path} className="cf-field">
          <span className="cf-key">{field.key}</span>
          <span className="cf-ctl">
            <FieldControl field={field} onChange={(value) => onChange(field.path, value)} />
            {headOverrideOf(field.path) === null ? null : <Button small onClick={() => onChange(field.path, '')}>{CF.removeOverride}</Button>}
          </span>
        </div>
      ))}
    </section>
  );
}

/** The whole splice.toml as typed controls, grouped by its top-level tables, with what will change listed before it is written. The
 *  write is the daemon's structured writer, which backs the file up first and is the authority on what it accepts. */
export function ConfigFile() {
  const topology = useTopology();
  const edit = useTopologyEdit();
  const [open, setOpen] = useState(false);
  const [draft, setDraft] = useState<Record<string, unknown> | null>(null);
  const payload = topology.data;
  if (topology.isError) return <p className="hint alert row-note" role="alert">{failureText(topology.error)}</p>;
  if (payload === undefined) return null;
  if (isPendingRoute(payload)) return <p className="hint row-note">{CF.unavailable}</p>;
  const loaded = payload.topology;
  const working = draft ?? loaded;
  const changed = changedPaths(loaded, working);
  const groups = new Map<string, TopologyTable[]>();
  if (open) for (const table of topologyTables(working)) groups.set(groupOf(table.path), [...(groups.get(groupOf(table.path)) ?? []), table]);
  const findings = open ? validateTopology(working) : [];
  const result = edit.data;
  return (
    <>
      <Row
        title={CF.title}
        why={CF.why}
        note={<OnRequest label={CF.showPath}>{payload.path}</OnRequest>}
        control={<Button small aria-expanded={open} onClick={() => setOpen(!open)}>{open ? CF.close : CF.open}</Button>}
      />
      {!open ? null : (
        <div className="cf">
          {[...groups.entries()].map(([group, tables]) => (
            <details key={group} className="cf-group">
              <summary>{group === '' ? CF.topLevel : CF.groups[group] ?? group}<small>{tables.reduce((sum, table) => sum + table.fields.length, 0)}</small></summary>
              {tables.map((table) => <TableView key={table.path} table={table} group={group} onChange={(path, value) => setDraft(setAtPath(working, path, value))} />)}
            </details>
          ))}
          {findings.length === 0 ? null : (
            <div role="alert">
              <p className="hint alert">{CF.problems}</p>
              <ul className="cf-list">{findings.map((finding) => <li key={finding.path}>{finding.path}: {finding.message}</li>)}</ul>
            </div>
          )}
          <p className="hint" role="status">{CF.changes(changed.length)}</p>
          {changed.length === 0 ? null : (
            <ul className="cf-list">
              {changed.map((path) => <li key={path}><code>{path}</code>: {shown(valueAtPath(working, path))} <small>{CF.from} {shown(valueAtPath(loaded, path))}</small></li>)}
            </ul>
          )}
          <div className="try">
            <Button kind="go" disabled={changed.length === 0 || edit.isPending} onClick={() => edit.mutate({ topology: working, keys: changed }, { onSuccess: () => setDraft(null) })}>{edit.isPending ? CF.writing : CF.write}</Button>
          </div>
          {result === undefined || !result.ok || changed.length > 0 ? null : <p className="hint" role="status">{CF.written}{result.backup_path === undefined ? '' : ` ${CF.backup} ${result.backup_path}.`}</p>}
          {(result?.findings ?? []).map((finding) => <p key={finding.path} className="hint alert" role="alert">{finding.path}: {finding.message}</p>)}
          {edit.isError ? <p className="hint alert" role="alert">{CF.failed} {failureText(edit.error)}</p> : null}
          <details className="cf-raw">
            <summary>{CF.raw}</summary>
            <p className="hint">{CF.rawWhy}</p>
            <pre>{toToml(working)}</pre>
          </details>
        </div>
      )}
    </>
  );
}
