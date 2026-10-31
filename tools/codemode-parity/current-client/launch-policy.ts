import {appendFileSync,readFileSync}from"node:fs";
const restricted=new Set(["CODEX_EXEC_SERVER_NOISE_AUTH_TOKEN","NODE_REPL_AUTH_TOKEN","CODEX_GUARDIAN_DECISIONS_API_KEY","OPENAI_FEDERATION_RULE_ID","OPENAI_IDENTITY_TOKEN_FILE","OPENAI_WORKLOAD_IDENTITY_CONTEXT"]);
if(process.env.SPLICE_HOST_LAUNCH_POLICY_PATH){
 const pid=process.ppid,stat=readFileSync("/proc/"+pid+"/stat","utf8"),group=Number(stat.slice(stat.lastIndexOf(")")+2).split(" ")[2]);
 appendFileSync(process.env.SPLICE_HOST_LAUNCH_POLICY_PATH,JSON.stringify({restrictedNamesPresent:Object.keys(process.env).filter(name=>restricted.has(name.toUpperCase())).sort(),pid,processGroup:group,hasOwnProcessGroup:pid===group})+"\n");
}

