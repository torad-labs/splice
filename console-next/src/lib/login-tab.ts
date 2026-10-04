// The provider's sign-in page, opened in a tab. A tab opened after the login request answers is blocked as a popup, so it
// opens in the click's own gesture and is pointed at the provider once the daemon announces the page.
import type { LoginView } from '../types/core';
import { H, S } from './words-login';

/** The parts of a window this reads, so a test can hand in a stand-in. */
export interface SignInTab {
  opener: unknown;
  document: { title: string; body: { textContent: string | null } | null };
  location: { href: string };
  close: () => void;
}

/** Only a provider's web page is worth a tab; a malformed or non-https address is never turned into navigation. */
export function providerPage(url: string | null): string | null {
  if (url === null || !URL.canParse(url)) return null;
  return new URL(url).protocol === 'https:' ? url : null;
}

/** Opens the tab. Null when the browser refused it, in which case the page's own link is the way. */
export function openSignInTab(open: (url: string, target: string) => SignInTab | null = (url, target) => window.open(url, target)): SignInTab | null {
  const tab = open('', '_blank');
  if (tab === null) return null;
  tab.opener = null;
  tab.document.title = S.opening;
  if (tab.document.body !== null) tab.document.body.textContent = H.opening;
  return tab;
}

/** Points the tab at the announced page and lets go of it; closes it when the login ended without one. Returns the tab
 *  while it still waits for the daemon to announce a page. */
export function advanceSignInTab(tab: SignInTab | null, status: LoginView | null, error: string | null): SignInTab | null {
  if (tab === null) return null;
  const url = providerPage(status?.browser_url ?? null) ?? providerPage(status?.verification_uri ?? null);
  if (url !== null) {
    tab.location.href = url;
    return null;
  }
  const announced = status !== null && (status.browser_url !== null || status.verification_uri !== null);
  if (error !== null || announced || status?.state === 'failed' || status?.state === 'signed_in' || status?.state === 'live_after_restart') {
    tab.close();
    return null;
  }
  return tab;
}
