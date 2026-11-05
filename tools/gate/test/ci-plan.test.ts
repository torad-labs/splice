import { expect, test } from 'bun:test';
import { partitionGraph, verifyReceipts, type TaskNode, type Receipt } from '../src/ci/plan.ts';
const t=(path:string,deps:string[]=[],kind='task'):TaskNode=>({path,deps,kind,actions:true});
const graph=[t(':core:compileKotlin'),t(':core:compileTestKotlin',[':core:compileKotlin']),t(':core:test',[':core:compileTestKotlin'],'test'),t(':app:compileKotlin',[':core:compileKotlin']),t(':app:test',[':app:compileKotlin'],'test'),t(':app:shadowJar',[':app:compileKotlin']),t(':oracleReplay',[':app:shadowJar'],'ladder'),t(':consoleTests',[],'ladder'),t(':quality-architecture:test',[':core:compileTestKotlin'],'test'),t(':core:detekt',[],'verification'),t(':gateOfRecord',[':core:test',':app:test',':oracleReplay',':consoleTests',':quality-architecture:test',':core:detekt'],'aggregate')];
test('every selected task is executed or supplied as a dependency by independent owners',()=>{
 const plan=partitionGraph(graph,[],4);
 expect(plan.workers.find(w=>w.id==='architecture')?.tasks).toContain(':quality-architecture:test');
 expect(plan.workers.find(w=>w.id==='browser')?.tasks).toEqual([':consoleTests']);
 expect(plan.workers.find(w=>w.id==='packaged')?.tasks).toContain(':oracleReplay');
 const owners=plan.workers.flatMap(w=>w.tasks);
 for(const task of [':core:test',':app:test',':core:detekt',':oracleReplay',':consoleTests',':quality-architecture:test'])expect(owners).toContain(task);
 expect(plan.workers.filter(w=>w.id.startsWith('jvm-')).length).toBe(4);
});
test('bad dependency graphs refuse instead of dropping unknown obligations',()=>{
 expect(()=>partitionGraph([t(':a',[':missing'])],[],4)).toThrow();
 expect(()=>partitionGraph([t(':a',[':b']),t(':b',[':a'])],[],4)).toThrow();
 expect(()=>partitionGraph([t(':a'),t(':a')],[],4)).toThrow();
});
test('new executable check cannot silently fall outside the plan',()=>{
 const plan=partitionGraph([...graph,t(':newCheck',[], 'task')],[],4);
 expect(plan.workers.flatMap(w=>w.tasks)).toContain(':newCheck');
});
test('collector refuses missing duplicate failed or different-candidate receipts',()=>{
 const plan=partitionGraph(graph,[],4);
 const receipts:Receipt[]=plan.workers.map(w=>({worker:w.id,candidate:'sha',plan:'digest',ok:true,tasks:w.tasks,direct:w.direct}));
 expect(()=>verifyReceipts(plan,receipts,'sha','digest')).not.toThrow();
 expect(()=>verifyReceipts(plan,receipts.slice(1),'sha','digest')).toThrow();
 expect(()=>verifyReceipts(plan,[...receipts,receipts[0]!],'sha','digest')).toThrow();
 expect(()=>verifyReceipts(plan,receipts.map((r,i)=>i? r:{...r,ok:false}),'sha','digest')).toThrow();
 expect(()=>verifyReceipts(plan,receipts,'other','digest')).toThrow();
 expect(()=>verifyReceipts(plan,receipts,'sha','stale')).toThrow();
});
test('generated task graphs retain every terminal action and use acyclic prerequisites',()=>{
 for(let size=1;size<35;size++){
  const tasks=Array.from({length:size},(_,i)=>t(`:m${i}:custom`,Array.from({length:i},(_,j)=>j).filter(j=>(j+i)%3===0).map(j=>`:m${j}:custom`)));
  const plan=partitionGraph(tasks,[],4),owned=new Set(plan.workers.flatMap(w=>w.tasks));
  const reached=new Set<string>();const visit=(id:string)=>{if(reached.has(id))return;reached.add(id);for(const d of tasks.find(t=>t.path===id)!.deps)visit(d);};for(const id of owned)visit(id);
  expect([...reached].sort()).toEqual(tasks.map(t=>t.path).sort());
 }
});
