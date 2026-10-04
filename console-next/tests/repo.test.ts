// What a repository is called: the name it was cloned as, else its folder, never a bare "repo".
import { describe, expect, test } from 'vitest';
import { repoNameOf } from '../src/lib/repo';

describe('repoNameOf', () => {
  test('the remote names the repo, in every URL form', () => {
    expect(repoNameOf('/x/mythos/repo', 'https://github.com/torad-labs/splice.git')).toBe('splice');
    expect(repoNameOf('/x/mythos/repo', 'git@github.com:torad-labs/splice.git')).toBe('splice');
    expect(repoNameOf('/x/mythos/repo', 'https://github.com/torad-labs/splice/')).toBe('splice');
    expect(repoNameOf('/x/mythos/repo', 'ssh://host/srv/git/ledger')).toBe('ledger');
  });
  test('with no remote the folder names it, beside its parent when the folder says nothing', () => {
    expect(repoNameOf('/home/ava/code/tally')).toBe('tally');
    expect(repoNameOf('/home/ava/code/tally/')).toBe('tally');
    expect(repoNameOf('/home/ava/mythos/repo')).toBe('mythos/repo');
    expect(repoNameOf('/home/ava/mythos/repo', '')).toBe('mythos/repo');
    expect(repoNameOf('/home/ava/mythos/repo', null)).toBe('mythos/repo');
    expect(repoNameOf('/')).toBe('/');
  });
});
