import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, test } from 'vitest';
import type { AddView } from '../src/entities/add';
import type { PlaygroundWire } from '../src/entities/playground';
import { OpenAdd, SavedAdd, polledAdd } from '../src/widgets/add-backend';
import { autoSaveTarget, tryReply } from '../src/widgets/add-backend/model';

const connected: AddView = {
  id: 'add-1', profile: 'codex', key: 'codex', command: 'claudex', auth_kind: 'chatgpt-oauth',
  base_url: null, models: [], sign_in_by: 'login', key_env: null,
  credential: { present: true, detail: 'signed in' },
  sign_in: { id: 'login-1', state: 'signed_in', browser_url: null, verification_uri: null, user_code: null, failure_reason: null },
  checks: null, saved: null,
};

const action = (view: AddView) => renderToStaticMarkup(createElement(OpenAdd, {
  view, checks: null, busy: false, onSignIn: () => undefined, onVerify: () => undefined,
  onSave: () => undefined, onDiscard: () => undefined,
}));

describe('a sign-in connects its plan', () => {
  test('OAuth signed_in saves once without a Save backend click, but key profiles still require Save', () => {
    expect(autoSaveTarget(connected, null)).toBe('add-1');
    expect(autoSaveTarget(connected, 'add-1')).toBeNull();
    const alreadySignedIn = { ...connected, sign_in: null };
    expect(autoSaveTarget(alreadySignedIn, null)).toBe('add-1');
    expect(action(alreadySignedIn)).not.toContain('Save provider');
    const signIn = connected.sign_in;
    if (signIn === null) throw new Error('missing login fixture');
    expect(autoSaveTarget({ ...connected, sign_in: { ...signIn, state: 'waiting' } }, null)).toBeNull();
    expect(autoSaveTarget({ ...connected, sign_in_by: 'key' }, null)).toBeNull();
    expect(action(connected)).not.toContain('Save provider');
    expect(action({ ...connected, sign_in_by: 'key' })).toContain('Save provider');
    const failed = renderToStaticMarkup(createElement(OpenAdd, {
      view: connected, checks: null, busy: false, saveFault: 'save refused',
      onSignIn: () => undefined, onVerify: () => undefined,
      onSave: () => undefined, onDiscard: () => undefined,
    }));
    expect(failed).toContain('Retry connection');
  });

  test('a failed auto-save resumes fresh credential reads without retrying the same add', () => {
    const revoked = { ...connected, credential: { present: false, detail: 'Sign in again' } };
    expect(polledAdd(connected, revoked, true)).toEqual(connected);
    expect(polledAdd(connected, revoked, false)).toEqual(revoked);
    expect(autoSaveTarget(revoked, connected.id)).toBeNull();
    expect(polledAdd({ ...connected, saved: {
      path: '/work/splice.toml', wrapper: { linked: true }, restart: { status: 'draining' },
    } }, revoked, false)?.saved).not.toBeNull();
  });

  test('the connected command is shown as one line with Copy and a restart step if refused', () => {
    const view: AddView = { ...connected, saved: {
      path: '/work/splice.toml', wrapper: { linked: true }, restart: { status: 'refused', error: 'unsupervised daemon' },
    } };
    const html = renderToStaticMarkup(createElement(SavedAdd, { view, live: false }));
    expect(html).toContain('ChatGPT saved');
    expect(html).toContain('splice restart');
    expect(html).not.toContain('ChatGPT connected');
    expect(html).not.toContain('Try it');
    const live = renderToStaticMarkup(createElement(SavedAdd, { view, live: true }));
    expect(live).toContain('ChatGPT connected');
    expect(live).toContain('Run claudex');
    expect(live).toContain('Copy');
    expect(live).toContain('Try it');
  });

  test('the one real provider test reads model and reply without inventing a success on HTTP error', () => {
    const response: PlaygroundWire = {
      request: { url: 'https://provider.example/responses', method: 'POST', headers: {}, body: { model: 'gpt-6-sol', input: 'Say hello' } },
      response: { status: 200, body: { model: 'gpt-6-sol', output: [{ type: 'message', content: [{ type: 'output_text', text: 'Hello from Sol' }] }] } },
    };
    expect(tryReply(response)).toEqual({ model: 'gpt-6-sol', text: 'Hello from Sol' });
    expect(tryReply({ ...response, response: { status: 429, body: { error: { message: 'Limit reached' } } } })).toEqual({ error: 'Limit reached' });
    expect(tryReply({ ...response, response: { status: 200, body: { error: { message: 'Provider refused this model' } } } }))
      .toEqual({ error: 'Provider refused this model' });
    expect(tryReply({ ...response, response: { status: 200, body: { model: 'gpt-6-sol', choices: [{ message: { content: 'Chat reply' } }] } } }))
      .toEqual({ model: 'gpt-6-sol', text: 'Chat reply' });
    expect(tryReply({ ...response, response: { status: 200, body: { model: 'muse', content: [{ type: 'text', text: 'Muse reply' }] } } }))
      .toEqual({ model: 'muse', text: 'Muse reply' });
    expect(tryReply({ ...response, response: { status: 200, body: { model: 'gpt-6-sol', output: [] } } }))
      .toEqual({ error: 'The provider returned no readable reply.' });
    expect(tryReply({ ...response, response: { status: 200, body: { model: 'gpt-6-sol', output_text: '' } } }))
      .toEqual({ error: 'The provider returned no readable reply.' });
    expect(tryReply({ ...response, response: { status: 200, body: { model: 'gpt-6-sol', output: [{ content: [{ type: 'output_text', text: '' }] }] } } }))
      .toEqual({ error: 'The provider returned no readable reply.' });
    expect(tryReply({ ...response, response: { status: 200, body: { model: 'gpt-6-sol', output: [{ content: [{ type: 'output_text', text: '  ' }] }] } } }))
      .toEqual({ error: 'The provider returned no readable reply.' });
  });
});
