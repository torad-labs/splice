import { expect, test } from 'bun:test';
import { gradleModules } from '../src/commands/hook.ts';
import { ciSelection } from '../src/lib/ci-selection.ts';
import { layout } from '../src/lib/repo.ts';
const root=layout().repoRoot;
test('a production dependency change selects App in CI',()=>{
 const scope=ciSelection(root,['features/turns/src/main/kotlin/splice/head/HeadDeps.kt']);
 expect(scope.gradle).toContain(':app:check');
 expect(scope.gradle).toContain(':features-turns:check');
});
test('fixture changes select their test-only consumers',()=>{
 const scope=ciSelection(root,['features/turns/src/testFixtures/kotlin/Fixture.kt']);
 expect(scope.gradle).toContain(':integrations-providers-grok:check');
 expect(scope.gradle).toContain(':integrations-providers-openai:check');
});
test('changing the CI execution adapter exercises all module checks',()=>{
 const scope=ciSelection(root,['tools/gate/ci.init.gradle']);
 for(const module of gradleModules(root))expect(scope.gradle).toContain(`${module.path}:check`);
});
test('all declared modules participate in a full-scope change',()=>{
 const scope=ciSelection(root,['settings.gradle.kts']);
 for(const module of gradleModules(root))expect(scope.gradle).toContain(`${module.path}:check`);
});
test('unread prose does not acquire JVM work',()=>{
 expect(ciSelection(root,['docs/notes/a-new-note.md']).gradle).toEqual([]);
});
