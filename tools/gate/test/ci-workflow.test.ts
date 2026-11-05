import {expect,test} from 'bun:test';
import {readFileSync} from 'node:fs';
import {join} from 'node:path';
import {layout} from '../src/lib/repo.ts';
const workflow=Bun.YAML.parse(readFileSync(join(layout().repoRoot,'.github/workflows/ci.yml'),'utf8')) as {jobs:Record<string,any>};
test('the existing required context joins all worker results without advisory workflows',()=>{
 expect(workflow.jobs.gate.needs).toEqual(['plan','workers','macos']);
 expect(workflow.jobs.gate.if).toContain('!cancelled()');
 expect(workflow.jobs.workers.strategy['fail-fast']).toBe(false);
 expect(workflow.jobs.workers.needs).toBe('plan');
 expect(workflow.jobs.macos.needs).toBeUndefined();
});
test('verified main artifacts are retained only after receipt reconciliation',()=>{
 const steps=workflow.jobs.gate.steps as {name?:string;run?:string;uses?:string;with?:Record<string,string>}[];
 const collect=steps.findIndex(s=>s.run?.includes('ci collect'));
 const retained=steps.findIndex(s=>s.with?.name==='splice-jar');
 expect(collect).toBeGreaterThan(0);expect(retained).toBeGreaterThan(collect);
});
