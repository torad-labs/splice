import { dirname } from "node:path";
import { sha256 } from "./qualification";
import type { ScopeFile } from "./current-foreign-scope";

export type GeneratedProtoSymbol = { selectors: Array<{ path: string; sha256: string }>; schema: string[] };
const snake = (name: string) => name.replace(/([a-z0-9])([A-Z])/g,"$1_$2").replace(/([A-Z]+)([A-Z][a-z])/g,"$1_$2").toLowerCase();
const pascal = (name: string) => name.toLowerCase().replace(/(?:^|_)([a-z0-9])/g,(_all,char:string)=>char.toUpperCase());
/** Resolve only generated names justified by exact include_proto/build/schema sources. */
export function generatedProtoSymbols(files: ScopeFile[], moduleName: (file:string)=>string|undefined): Map<string,GeneratedProtoSymbol> {
  const result = new Map<string,GeneratedProtoSymbol>();
  for(const module of files.filter(file=>/tonic::include_proto!\s*\(/.test(file.source))) {
    const packageName=module.source.match(/tonic::include_proto!\s*\(\s*"([^"]+)"\s*\)/)?.[1], prefix=moduleName(module.path);
    const sourceRoot=module.path.slice(0,module.path.indexOf("/src/")), build=files.find(file=>file.path===sourceRoot+"/build.rs");
    if(!packageName||!prefix||!build||!/tonic_prost_build::configure\s*\(/.test(build.source)||!/compile_with_config\s*\(/.test(build.source)||!/glob::glob\("src\/grpc\/\*\.proto"\)/.test(build.source))continue;
    for(const schema of files.filter(file=>dirname(file.path)===dirname(module.path)&&file.path.endsWith(".proto"))) {
      if(schema.source.match(/\bpackage\s+([\w.]+)\s*;/)?.[1]!==packageName)continue;
      const clean=schema.source.replace(/\/\*[\s\S]*?\*\//g,value=>value.replace(/[^\n]/g," ")).replace(/\/\/[^\n]*/g,value=>" ".repeat(value.length));
      type Block={kind:string;name:string;start:number;open:number;end:number;parent?:Block};
      const blocks:Block[]=[];
      for(const match of clean.matchAll(/\b(message|enum|oneof|service)\s+(\w+)\s*\{/g)){
        const open=match.index!+match[0].lastIndexOf("{");let depth=1,end=open+1;for(;end<clean.length&&depth;end++){if(clean[end]==="{")depth++;if(clean[end]==="}")depth--;}
        if(depth)throw new Error("unterminated pinned protobuf declaration");
        blocks.push({kind:match[1]!,name:match[2]!,start:match.index!,open,end});
      }
      for(const block of blocks)block.parent=blocks.filter(parent=>parent!==block&&parent.open<block.start&&block.end<=parent.end).sort((a,b)=>(a.end-a.start)-(b.end-b.start))[0];
      const selectors=[module,build,schema].map(file=>({path:file.path,sha256:sha256(file.source)}));
      const unit=(kind:string,start:number)=>schema.path+"#proto-"+kind+"@"+Buffer.byteLength(clean.slice(0,start));
      const put=(path:string,id:string)=>{const entry=result.get(path)??{selectors,schema:[]};if(!entry.schema.includes(id))entry.schema.push(id);result.set(path,entry);};
      const modulePath=(block:Block):string=>block.parent ? modulePath(block.parent)+"::"+snake(block.parent.name):prefix;
      for(const block of blocks){
        const path=modulePath(block)+"::"+(block.kind==="oneof"?block.name.replace(/(?:^|_)([a-z])/g,(_all,char:string)=>char.toUpperCase()):block.name);
        const id=unit(block.kind,block.start);
        if(["message","enum","oneof"].includes(block.kind))put(path,id);
        if(block.kind==="message")put(path+"::default",id);
        if(block.kind==="enum"){
          put(path+"::try_from",id);
          for(const match of clean.slice(block.open+1,block.end-1).matchAll(/\b(\w+)\s*=\s*\d+/g)){
            const name=match[1]!,lead=snake(block.name).toUpperCase()+"_",variant=pascal(name.startsWith(lead)?name.slice(lead.length):name);
            put(path+"::"+variant,unit("enum-value",block.open+1+match.index!));
          }
        }
        if(block.kind==="oneof")for(const match of clean.slice(block.open+1,block.end-1).matchAll(/\b(?:repeated\s+|optional\s+)?[.\w]+\s+(\w+)\s*=\s*\d+/g))put(path+"::"+pascal(match[1]!),unit("field",block.open+1+match.index!));
        if(block.kind==="service"){
          for(const side of ["client","server"]){
            const servicePath=prefix+"::"+snake(block.name)+"_"+side+"::"+block.name+(side==="client"?"Client":"Server");
            put(servicePath,id);
            for(const constructor of ["new","with_origin"])put(servicePath+"::"+constructor,id);
            for(const match of clean.slice(block.open+1,block.end-1).matchAll(/\brpc\s+(\w+)/g))put(servicePath+"::"+snake(match[1]!),unit("rpc",block.open+1+match.index!));
          }
        }
      }
      // A module reference is valid only if at least one exact generated child exists.
      for(const [path,entry]of [...result])if(entry.selectors.some(item=>item.path===schema.path))for(let parts=path.split("::").slice(0,-1);parts.length>prefix.split("::").length;parts=parts.slice(0,-1))for(const id of entry.schema)put(parts.join("::"),id);
    }
  }
  for(const value of result.values())value.schema.sort();
  return result;
}

