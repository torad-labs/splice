// The login's wire, read from the daemon's source. The console first typed the login from the plan
// (FEATURES.md 6: login_id, flow, a landed state) and no check read the daemon: the wire-keys probe
// reads GETs of a booted daemon, and a login's poll exists only after a POST that would reach a real
// provider. So a login never polled and never finished against a real daemon, and every fixture
// agreed with the type it was written from.
//
// THE DENOMINATOR IS THE KOTLIN: every key the daemon's two login serializers put, and every wire of
// its LoginState. The account login's (LoginRoutes) is the LoginStatusPayload the console reads; a
// console add's sign-in (AddViews) is the same LoginView with no head, since its head is in no file.
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, test } from 'vitest';
import { LOGIN_STATES } from '../src/shared/api';
import type { LoginStatusPayload, LoginView } from '../src/entities/auth';

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const read = (relative: string): string => readFileSync(path.join(repoRoot, relative), 'utf8');

const STATE_ENUM = 'features/accounts/src/main/kotlin/splice/accounts/signin/ConsoleAccounts.kt';

/** The keys one serializer puts: the `put("…")` calls in the body of `fun <name>(status: LoginStatus)`. */
function putKeys(source: string, fn: string): string[] {
  const head = source.search(new RegExp(`fun ${fn}\\(status: LoginStatus\\)`));
  if (head < 0) throw new Error(`no fun ${fn}(status: LoginStatus) in the source`);
  const body = source.slice(head, source.indexOf('\n    }', head));
  return [...body.matchAll(/put\("([a-z_]+)"/g)].flatMap(([, key]) => (key === undefined ? [] : [key]));
}

/** The wire of every LoginState constant. */
function stateWires(source: string): string[] {
  const head = source.indexOf('enum class LoginState');
  if (head < 0) throw new Error('no enum class LoginState in the source');
  const body = source.slice(head, source.indexOf('}', head));
  return [...body.matchAll(/[A-Z_]+\("([a-z_]+)"\)/g)].flatMap(([, wire]) => (wire === undefined ? [] : [wire]));
}

// Every key the console declares, required or not: `Required` makes the compiler hold each object to
// its whole interface, so a key a type gains or loses moves its list with it.
const VIEW: Required<LoginView> = {
  id: '', state: 'starting', user_code: null, verification_uri: null, browser_url: null, failure_reason: null,
};
const STATUS: Required<LoginStatusPayload> = { ...VIEW, head: '' };

const SERIALIZERS = [
  { file: 'features/accounts/src/main/kotlin/splice/accounts/signin/LoginRoutes.kt', fn: 'loginStatusJson', type: 'LoginStatusPayload', declared: STATUS },
  { file: 'features/configuration/src/main/kotlin/splice/configuration/add/AddViews.kt', fn: 'login', type: 'LoginView', declared: VIEW },
] as const;

describe('the login status the console reads is the one the daemon writes', () => {
  for (const { file, fn, type, declared } of SERIALIZERS) {
    test(`${path.basename(file)} ${fn}() puts exactly the keys ${type} declares`, () => {
      const keys = putKeys(read(file), fn);
      expect(keys.length, 'the serializer was found and read').toBeGreaterThan(0);
      expect([...keys].sort()).toEqual(Object.keys(declared).sort());
    });
  }

  test('LOGIN_STATES are the daemon\'s LoginState wires, every one', () => {
    const wires = stateWires(read(STATE_ENUM));
    expect(wires.length, 'the enum was found and read').toBeGreaterThan(0);
    expect([...LOGIN_STATES].sort()).toEqual([...wires].sort());
  });
});
