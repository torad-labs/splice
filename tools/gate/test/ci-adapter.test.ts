import { expect, test } from "bun:test";
import { mkdtempSync, mkdirSync, writeFileSync, readFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { runUnderSlot } from "../src/lib/slot.ts";
import { layout } from "../src/lib/repo.ts";

// A real tiny Gradle/Java/JUnit project exercises the init adapter without building Splice.
// Opt-in for the CI integration job and the implementation's targeted TDD run.
const integration = process.env.SPLICE_CI_ADAPTER_TEST === "1" ? test : test.skip;
integration("real Gradle graph and two class workers preserve every test and producer", async () => {
  const root = mkdtempSync(join(tmpdir(), "splice-ci-adapter-")), repo = layout();
  try {
    writeFileSync(join(root,"settings.gradle"),"rootProject.name='ci-fixture'\n");
    writeFileSync(join(root,"build.gradle"),"plugins { id 'java' }\nrepositories { mavenCentral() }\ndependencies { testImplementation 'junit:junit:4.13.2' }\n");
    mkdirSync(join(root,"src/test/java/sample"),{recursive:true});
    for(let i=0;i<8;i++)writeFileSync(join(root,`src/test/java/sample/Case${i}Test.java`),`package sample; public class Case${i}Test { @org.junit.Test public void result() { org.junit.Assert.assertEquals(4,2+2); } }`);
    const weights: Record<string, number> = { "sample.Case0Test": 8 };
    for(let i=0;i<8;i++){
      writeFileSync(join(root,`src/test/java/sample/AHelper${i}.java`),`package sample; public class AHelper${i} {}`);
      weights[`sample.AHelper${i}`] = 0;
    }
    const init=join(repo.repoRoot,"tools/gate/ci.init.gradle"),graph=join(root,"graph.json");
    const run=(args:string[],env:Record<string,string>)=>runUnderSlot({layout:repo,label:"ci-adapter-test",args:["-p",root,"--no-configuration-cache","--no-build-cache","-I",init,...args],env:{...env,SPLICE_CI_ROOT:root}});
    expect(await run(["test","--dry-run"],{SPLICE_CI_GRAPH:graph})).toBe(0);
    const tasks=JSON.parse(readFileSync(graph,"utf8")) as {path:string;kind:string}[];
    expect(tasks.find(t=>t.path===":test")?.kind).toBe("test");
    const observed=new Set<string>();
    for(let index=0;index<2;index++){
      const config=join(root,`shard-${index}.json`),output=join(root,`proof-${index}`);
      writeFileSync(config,JSON.stringify({tasks:[":test"],index,count:2,weights,output}));
      expect(await run(["cleanTest","test"],{SPLICE_CI_SHARDS:config})).toBe(0);
      const manifest=JSON.parse(readFileSync(join(output,"_test.json"),"utf8")) as {selected:string[]};
      // One indivisible cost-8 test and seven cost-1 tests fit in loads 8 and 7.
      // Zero-cost helpers must not distort that balance, but still have exactly one owner.
      expect(manifest.selected.reduce((sum,name)=>sum+(weights[name] ?? 1),0)).toBeLessThanOrEqual(8);
      for(const name of manifest.selected){
        expect(observed.has(name)).toBe(false);observed.add(name);
        if(name.endsWith("Test"))expect(readFileSync(join(root,`build/test-results/test/TEST-${name}.xml`),"utf8")).toContain('tests="1"');
      }
    }
    expect(observed.size).toBe(16);
  } finally { rmSync(root,{recursive:true,force:true}); }
},180_000);
