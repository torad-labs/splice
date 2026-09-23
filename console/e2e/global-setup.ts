// Boots the live stack once for the whole run and hands the tests its address and key through the
// environment (Playwright propagates what globalSetup sets to every worker). The returned function
// is Playwright's teardown: the daemon and the mock stop whether the run passed or failed.
import { startStack } from './stack';

export default async function globalSetup(): Promise<() => Promise<void>> {
  const stack = await startStack();
  process.env.CONSOLE_E2E_BASE = stack.base;
  process.env.CONSOLE_E2E_KEY = stack.key;
  return stack.stop;
}
