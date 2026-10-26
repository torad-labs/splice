//! Data-type shims surround unchanged source modules extracted from the pinned Git commit.
extern crate self as codex_protocol;
extern crate self as codex_code_mode;
extern crate self as codex_utils_string;
use serde::Serialize;
use serde_json::{Value, json};
use std::collections::BTreeMap;
use std::io::{self, BufRead};
use bm25::{DefaultTokenizer, Tokenizer, Language, Embedder, EmbedderBuilder, Scorer};
#[path="source-witness.rs"] mod source_witness;

pub const PUBLIC_TOOL_NAME: &str = "exec";
pub const WAIT_TOOL_NAME: &str = "wait";
pub const DEFAULT_TOOL_NAMESPACE: &str = "functions";
#[path = "tool_name.rs"] mod tool_name;
pub use tool_name::ToolName;
#[path = "description.rs"] mod description;
#[path = "json_schema_types.rs"] mod json_schema_types;
pub use description::*;
pub use json_schema_types::DEFAULT_INPUT_SCHEMA_MAX_BYTES;
#[path="current-format-oracle.rs"] mod format_oracle;
#[path="current-arguments-oracle.rs"] mod arguments_oracle;
pub use format_oracle::{models,tools,mcp,protocol,ImageDetail,FunctionCallOutputContentItem};
pub use format_oracle::native_string::*;
pub mod openai_models {
    use serde::Deserialize;
    #[derive(Deserialize)] pub struct CodeModeExecToolMessages { pub description: Option<String> }
    #[derive(Deserialize)] pub struct CodeModeToolMessages {
        pub exec: Option<CodeModeExecToolMessages>, pub deferred_nested_tools_guidance: Option<String>, pub mcp_typescript_preamble: Option<String>,
    }
    pub struct ModelInfo { pub supports_image_detail_original:bool }
}
#[path="typed-schema.rs"] mod typed_schema;
pub use typed_schema::JsonSchema as Parameters;
#[derive(Clone)] pub struct ToolOutputSchema(Value);
impl ToolOutputSchema { pub fn to_value(&self) -> Value { self.0.clone() } }
#[derive(Clone)] pub struct FunctionTool { pub name: String, pub description: String, pub parameters: Parameters, pub output_schema: Option<ToolOutputSchema> }
#[derive(Clone)] pub struct FreeformTool { pub name: String, pub description: String }
#[derive(Clone)] pub enum ResponsesApiNamespaceTool { Function(FunctionTool), Custom(FreeformTool) }
#[derive(Clone)] pub struct NamespaceTool { pub name: String, pub description: String, pub tools: Vec<ResponsesApiNamespaceTool> }
#[derive(Clone)] pub enum ToolSpec { Function(FunctionTool), Freeform(FreeformTool), Namespace(NamespaceTool), ToolSearch { parameters: Parameters }, WebSearch {} }
impl ToolSpec { pub fn name(&self) -> &str { match self { Self::Function(t)=>&t.name, Self::Freeform(t)=>&t.name, Self::Namespace(t)=>&t.name, Self::ToolSearch{..}=>"tool_search", Self::WebSearch{}=>"web_search" } } }
#[path = "tool_definitions.rs"] mod tool_definitions;

fn text(v: &Value, key: &str) -> String { v[key].as_str().unwrap_or_default().to_string() }
fn optional(v: &Value, key: &str) -> Option<Value> { v.get(key).filter(|v| !v.is_null()).cloned() }
fn usize_value(v: &Value, key: &str) -> Option<usize> { v[key].as_u64().map(|v|v as usize) }
fn parameters(v: &Value) -> Parameters { let mut schema:Parameters=serde_json::from_value(v.get("parameters").or(v.get("input_schema")).cloned().unwrap_or(json!({"type":"object"}))).expect("fixture outside constructible typed ToolSpec parameters");schema.mcp_input_schema_max_bytes=usize_value(v,"input_schema_max_bytes");schema }
fn function(v: &Value) -> FunctionTool { FunctionTool{name:text(v,"name"),description:text(v,"description"),parameters:parameters(v),output_schema:optional(v,"output_schema").map(ToolOutputSchema)} }
fn freeform(v: &Value) -> FreeformTool { FreeformTool{name:text(v,"name"),description:text(v,"description")} }
fn spec(v: &Value) -> ToolSpec { match v["type"].as_str().unwrap_or("function") {
    "namespace" => ToolSpec::Namespace(NamespaceTool{name:text(v,"name"),description:text(v,"description"),tools:v["tools"].as_array().unwrap().iter().map(|t|if t["type"]=="custom"||t["type"]=="freeform" {ResponsesApiNamespaceTool::Custom(freeform(t))}else{ResponsesApiNamespaceTool::Function(function(t))}).collect()}),
    "custom"|"freeform" => ToolSpec::Freeform(freeform(v)), "tool_search" => ToolSpec::ToolSearch{parameters:parameters(v)}, "web_search" => ToolSpec::WebSearch{}, _ => ToolSpec::Function(function(v)),
} }
fn definitions(v: &Value) -> Vec<ToolDefinition> {
    let mut specs=Vec::new();
    for tool in v["tools"].as_array().unwrap_or(&Vec::new()) {
        let mut t=tool.clone(); t["type"]=if tool["kind"]=="freeform"{json!("custom")}else{json!("function")};
        if let Some(namespace)=tool["namespace"].as_str() { specs.push(ToolSpec::Namespace(NamespaceTool{name:namespace.to_string(),description:text(tool,"namespace_description"),tools:vec![if tool["kind"]=="freeform"{ResponsesApiNamespaceTool::Custom(freeform(tool))}else{ResponsesApiNamespaceTool::Function(function(tool))}]})); }
        else { specs.push(spec(&t)); }
    }
    tool_definitions::collect_code_mode_tool_definitions(&specs,usize_value(v,"code_mode_input_schema_max_bytes"),v["tool_description_first"].as_bool().unwrap_or(false))
}
fn strip(mut tools:Vec<ToolDefinition>)->Value {for t in &mut tools{t.input_schema=None;t.output_schema=None;} json!({"prepared":tools})}
fn raw_definitions(v:&Value)->Vec<ToolDefinition> {v["tools"].as_array().unwrap_or(&Vec::new()).iter().map(|tool|ToolDefinition{
    name:tool_definitions::code_mode_name_for_tool_name(&ToolName::new(tool["namespace"].as_str().map(str::to_string),text(tool,"name"))),
    tool_name:ToolName::new(tool["namespace"].as_str().map(str::to_string),text(tool,"name")),description:text(tool,"description"),kind:if tool["kind"]=="freeform"{CodeModeToolKind::Freeform}else{CodeModeToolKind::Function},
    input_schema:optional(tool,"input_schema"),input_schema_max_bytes:usize_value(tool,"input_schema_max_bytes"),output_schema:optional(tool,"output_schema"),
}).collect()}
fn evaluate(v: &Value) -> Value {
    match v["operation"].as_str().unwrap() {
        "tokenize" => json!({"tokens":DefaultTokenizer::new(Language::English).tokenize(&text(v,"text"))}),
        "json_value" => {
            let value:Result<Value,serde_json::Error> = if let Some(hex)=v["bytes_hex"].as_str() {
                let bytes=(0..hex.len()).step_by(2).map(|index|u8::from_str_radix(&hex[index..index+2],16).expect("invalid fixture hex")).collect::<Vec<_>>();
                serde_json::from_slice(&bytes)
            } else { serde_json::from_str(v["source"].as_str().expect("missing fixture source")) };
            match value { Ok(value)=>json!({"value":value}),Err(error)=>json!({"error":error.to_string()}) }
        },
        "format" => format_oracle::evaluate(v),
        "tool_output" => format_oracle::evaluate_tool_output(v),
        "exec_arguments" | "wait_arguments" => arguments_oracle::evaluate(v),
        "rank" => {
            let corpus=v["corpus"].as_array().unwrap().iter().map(|text|text.as_str().unwrap()).collect::<Vec<_>>();
            let embedder:Embedder=EmbedderBuilder::with_fit_to_corpus(Language::English,&corpus).build();let mut scorer=Scorer::new();
            for(index,content)in corpus.iter().enumerate(){scorer.upsert(&index,embedder.embed(content));}
            let mut matches=scorer.matches(&embedder.embed(&text(v,"query")));matches.sort_by(|a,b|b.score.total_cmp(&a.score).then(a.id.cmp(&b.id)));
            json!({"matches":matches.iter().map(|matched|json!({"index":matched.id,"score":matched.score})).collect::<Vec<_>>()})
        },
        "prepare" => strip(definitions(v)),
        "augment" => strip(raw_definitions(v).into_iter().map(augment_tool_definition).collect()),
        "schema_projection" => json!({"schema":serde_json::from_value::<Parameters>(v["schema"].clone()).ok()}),
        "collect" => { let specs=v["specs"].as_array().unwrap().iter().map(spec).collect::<Vec<_>>();strip(tool_definitions::collect_code_mode_tool_definitions(&specs,usize_value(v,"code_mode_input_schema_max_bytes"),v["tool_description_first"].as_bool().unwrap_or(false))) },
        "describe" => {
            let raw=v["tools"].as_array().unwrap_or(&Vec::new()).iter().map(|tool|ToolDefinition{
                name:tool_definitions::code_mode_name_for_tool_name(&ToolName::new(tool["namespace"].as_str().map(str::to_string),text(tool,"name"))),
                tool_name:ToolName::new(tool["namespace"].as_str().map(str::to_string),text(tool,"name")),description:text(tool,"description"),kind:if tool["kind"]=="freeform"{CodeModeToolKind::Freeform}else{CodeModeToolKind::Function},
                input_schema:optional(tool,"input_schema"),input_schema_max_bytes:usize_value(tool,"input_schema_max_bytes"),output_schema:optional(tool,"output_schema"),
            }).collect::<Vec<_>>();
            let mut namespaces=BTreeMap::new();
            for tool in v["tools"].as_array().unwrap_or(&Vec::new()) {if let Some(name)=tool["namespace"].as_str(){namespaces.insert(name.to_string(),ToolNamespaceDescription{name:name.to_string(),description:text(tool,"namespace_description")});}}
            let opts=&v["options"];let mut messages=None;
            if let Some(m)=opts.get("messages"){messages=Some(openai_models::CodeModeToolMessages{exec:m.get("exec_description").map(|s|openai_models::CodeModeExecToolMessages{description:s.as_str().map(str::to_string)}),deferred_nested_tools_guidance:m["deferred_guidance"].as_str().map(str::to_string),mcp_typescript_preamble:m["mcp_preamble"].as_str().map(str::to_string)});}
            json!({"description":build_exec_tool_description(&raw,&[],&namespaces,opts["default_yield_time_ms"].as_u64().unwrap_or(30000),opts["code_mode_only"].as_bool().unwrap_or(true),if opts["image_detail_visible"].as_bool().unwrap_or(true){ImageDetailVisibility::Visible}else{ImageDetailVisibility::Hidden},if opts["discovery"]=="ranked_search"{DeferredToolDiscovery::RankedSearch}else{DeferredToolDiscovery::Catalog},messages.as_ref())})
        },
        "search" => {
            let entries = if let Some(entries)=v["entries"].as_array(){entries.clone()}else{v["tools"].as_array().unwrap_or(&Vec::new()).iter().map(|tool|json!({"tools":[tool],"search_text":format!("{} {}",text(tool,"name"),text(tool,"description"))})).collect()};
            let corpus=entries.iter().map(|entry|text(entry,"search_text")).collect::<Vec<_>>();
            if corpus.is_empty(){return json!({"tools":[]});}
            let corpus_refs=corpus.iter().map(String::as_str).collect::<Vec<_>>();
            let embedder:Embedder=EmbedderBuilder::with_fit_to_corpus(Language::English,&corpus_refs).build(); let mut scorer=Scorer::new();
            for(index,content)in corpus.iter().enumerate(){scorer.upsert(&index,embedder.embed(content));}
            let mut matches=scorer.matches(&embedder.embed(&text(&v["arguments"],"query"))); matches.sort_by(|a,b|b.score.total_cmp(&a.score).then(a.id.cmp(&b.id)));
            let allowed=v["allowed_names"].as_array().map(|values|values.iter().filter_map(Value::as_str).collect::<Vec<_>>());
            let limit=v["arguments"]["limit"].as_u64().unwrap_or(8) as usize;let mut selected=Vec::new();let mut seen=std::collections::BTreeSet::new();
            for matched in matches {for tool in entries[matched.id]["tools"].as_array().unwrap(){let mut input=json!({"tools":[tool]});input["tool_description_first"]=v["tool_description_first"].clone();for definition in definitions(&input){if allowed.as_ref().is_some_and(|names|!names.contains(&definition.name.as_str()))||!seen.insert(definition.name.clone()){continue;}selected.push(json!({"name":normalize_code_mode_identifier(&definition.name),"description":definition.description}));if selected.len()>=limit{return json!({"tools":selected});}}}}
            json!({"tools":selected})
        },
        _ => panic!("unknown current core fixture"),
    }
}
fn main(){ for line in io::stdin().lock().lines(){let v:Value=serde_json::from_str(&line.unwrap()).unwrap();source_witness::take();let result=evaluate(&v);let witnesses=source_witness::take();println!("{}",json!({"id":v["id"],"result":result,"source_witnesses":witnesses}));} }
