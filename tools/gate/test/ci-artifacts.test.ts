import {expect,test} from 'bun:test';
import {mkdtempSync,mkdirSync,symlinkSync,rmSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {safePath} from '../src/ci/files.ts';
test('artifact restoration refuses escapes and symlinked parent directories',()=>{
 const root=mkdtempSync(join(tmpdir(),'splice-ci-files-'));
 try{
  for(const path of ['../outside','/tmp/outside',''])expect(()=>safePath(root,path)).toThrow();
  mkdirSync(join(root,'inside'));expect(safePath(root,'inside/a.xml')).toBe(join(root,'inside/a.xml'));
  symlinkSync(tmpdir(),join(root,'escape'));expect(()=>safePath(root,'escape/a.xml')).toThrow();
 }finally{rmSync(root,{recursive:true,force:true});}
});
