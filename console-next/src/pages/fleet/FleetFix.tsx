import * as Menu from '@radix-ui/react-dropdown-menu';
import { useEffect, useRef, useState } from 'react';
import { failureText } from '../../api/client';
import { useSwitchAccount } from '../../api/auth';
import { useHeadAction } from '../../api/queries';
import { isExcluded } from '../../lib/accounts';
import { startCommandOf } from '../../lib/fleet';
import type { FleetFix as Fix } from '../../lib/fleet';
import type { AccountRow } from '../../types/accounts';
import type { HeadStatus } from '../../types/core';
import { Button, Chevron } from '../../ui';
import { SignIn } from '../shared/SignIn';
import { F } from './copy';

/** The one act a Fleet card offers, as the derivation named it. */
export function FleetFix({ fix, head, pool, now }: { fix: Fix; head: HeadStatus; pool: readonly AccountRow[]; now: number }) {
  const action = useHeadAction();
  const pin = useSwitchAccount();
  const [copied, setCopied] = useState(false);
  const timer = useRef<number | undefined>(undefined);
  useEffect(() => () => window.clearTimeout(timer.current), []);
  const failed = [action.error, pin.error].find((err) => err !== null && err !== undefined);
  const note = failed === undefined ? null : <span className="hint alert" role="alert">{F.failed} {failureText(failed)}</span>;

  switch (fix) {
    case 'start':
    case 'restart': {
      const busy = action.isPending;
      return (
        <>
          <Button kind="go" small disabled={busy} onClick={() => action.mutate({ head: head.key, action: fix })}>
            {fix === 'start' ? (busy ? F.starting : F.start) : busy ? F.restarting : F.restart}
          </Button>
          {note}
        </>
      );
    }
    case 'copy-start':
      return (
        <Button
          small
          onClick={() => {
            void navigator.clipboard.writeText(startCommandOf(head)).then(() => {
              setCopied(true);
              window.clearTimeout(timer.current);
              timer.current = window.setTimeout(() => setCopied(false), 2_000);
            });
          }}
        >
          {copied ? F.copied : F.copyStart}
        </Button>
      );
    case 'sign-in':
      return (
        <SignIn head={head.key} purpose="renew" {...(pool[0]?.label != null ? { label: pool[0].label } : {})}>
          <Button kind="go" small>{F.signIn}</Button>
        </SignIn>
      );
    case 'switch': {
      const choices = pool.filter((account) => account.label !== null && account.selected !== true && !isExcluded(account, now));
      return (
        <>
          <Menu.Root>
            <Menu.Trigger asChild>
              <Button kind="go" small disabled={pin.isPending}>
                {F.switchAccount}
                <Chevron />
              </Button>
            </Menu.Trigger>
            <Menu.Portal>
              <Menu.Content className="menu" align="start" sideOffset={6}>
                {choices.map((account) => (
                  <Menu.Item key={account.label} className="menu-item" onSelect={() => pin.mutate({ head: head.key, label: account.label ?? '' })}>
                    {account.label}
                  </Menu.Item>
                ))}
              </Menu.Content>
            </Menu.Portal>
          </Menu.Root>
          {note}
        </>
      );
    }
  }
}
