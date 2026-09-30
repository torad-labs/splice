import { useEffect, useRef } from 'react';
import { advanceSignInTab, openSignInTab } from '../../lib/login-tab';
import type { SignInTab } from '../../lib/login-tab';
import type { LoginView } from '../../types/core';

/** Holds the sign-in tab from the click that starts a login until the daemon announces its page. Call the returned
 *  function synchronously inside the click, before any request: a tab opened later is blocked. Polling carries on while
 *  the page is hidden behind the tab. */
export function useSignInTab(status: LoginView | null, error: string | null): () => void {
  const tab = useRef<SignInTab | null>(null);
  useEffect(() => {
    tab.current = advanceSignInTab(tab.current, status, error);
  }, [status, error]);
  useEffect(() => () => tab.current?.close(), []);
  return () => {
    tab.current?.close();
    tab.current = openSignInTab();
  };
}
