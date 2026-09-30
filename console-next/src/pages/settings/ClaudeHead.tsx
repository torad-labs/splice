import { failureText } from '../../api/client';
import { useClaudeHead, useUnwrapClaudeHead, useWrapClaudeHead } from '../../api/claude-head';
import { proseOf } from '../../lib/settings';
import { C } from '../../lib/words-claude';
import { Button, Confirm, State } from '../../ui';
import { T } from './copy';
import { Row } from './Row';

const Path = ({ children }: { children: string }) => <code className="path">{children}</code>;

/** Whether the claude command on this machine is splice's own separate plan or wrapped through splice. Two acts with different side
 *  effects (wrap edits two files in ~/.claude), so each asks once and prints what it backed up. */
export function ClaudeHead() {
  const head = useClaudeHead();
  const wrap = useWrapClaudeHead();
  const unwrap = useUnwrapClaudeHead();
  const card = head.data;
  if (head.isError) return <p className="hint alert row-note" role="alert">{failureText(head.error)}</p>;
  if (card === undefined) return <p className="hint row-note">{T.reading}</p>;
  const wrapped = card.mode === 'wrapped';
  const logins = card.claude_logins;
  const backups = !wrapped ? [] : [wrap.data?.settings_backup_path, wrap.data?.claude_json_backup_path].filter((path): path is string => path !== undefined);
  const act = wrapped
    ? <Confirm trigger={<Button>{C.unwrap}</Button>} title={C.unwrapTitle} why={C.unwrapWhy} act={C.unwrap} cancel={C.cancel} onConfirm={async () => void (await unwrap.mutateAsync())} />
    : <Confirm trigger={<Button>{C.wrap}</Button>} title={C.wrapTitle} why={C.wrapWhy} act={C.wrap} cancel={C.cancel} onConfirm={async () => void (await wrap.mutateAsync())} />;
  return (
    <>
      <Row title={C.title} why={wrapped ? C.wrappedWhy : C.separateWhy} control={<><State tone={wrapped ? 'wait' : 'work'}>{wrapped ? C.wrapped : C.separate}</State>{act}</>} />
      <Row title={C.onPath} why="" control={<Path>{card.resolves_to ?? C.notFound}</Path>} />
      <Row title={C.shim} why="" control={<Path>{card.shim_path}</Path>} />
      {wrapped ? <Row title={C.realBinary} why="" control={<Path>{card.real_binary_path ?? C.unknown}</Path>} /> : null}
      <Row title={C.logins} why={proseOf(logins.constraint)} control={<span>{logins.count === 0 ? C.noLogins : logins.labels.join(', ')}</span>} />
      <Row title={C.selected} why="" control={<span>{logins.selected ?? C.noneSelected}</span>} />
      {backups.map((path) => <Row key={path} title={C.backups} why="" control={<Path>{path}</Path>} />)}
    </>
  );
}
