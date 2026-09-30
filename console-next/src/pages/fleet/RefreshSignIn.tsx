import { failureText } from '../../api/client';
import { useRefreshLogin } from '../../api/auth';
import { Button } from '../../ui';
import { D } from './copy';

/** Asks the plan to renew its saved login now. `ok: false` is the daemon saying it did not happen, and its note is why. */
export function RefreshSignIn({ head }: { head: string }) {
  const refresh = useRefreshLogin();
  const result = refresh.data?.action === 'refresh' ? refresh.data.result : undefined;
  return (
    <p className="refresh">
      <Button small disabled={refresh.isPending} onClick={() => refresh.mutate(head)}>{refresh.isPending ? D.refreshing : D.refresh}</Button>
      {result === undefined ? null : result.ok ? <span className="hint" role="status">{D.refreshed}</span> : <span className="hint alert" role="alert">{D.refreshRefused} {result.note ?? ''}</span>}
      {refresh.isError ? <span className="hint alert" role="alert">{failureText(refresh.error)}</span> : null}
    </p>
  );
}
