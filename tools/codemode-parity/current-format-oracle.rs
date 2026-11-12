//! Wire/data types are local; the renderer, adapter, sanitizer and truncation source are unchanged.
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use std::time::Duration;

#[derive(Clone, Copy, Debug, Deserialize, Serialize)]
#[serde(rename_all="lowercase")]
pub enum ImageDetail { Auto, Low, High, Original }
#[derive(Clone, Deserialize)]
#[serde(tag="type",rename_all="snake_case")]
pub enum FunctionCallOutputContentItem {
    InputText{text:String}, InputImage{image_url:String,detail:Option<ImageDetail>}, InputAudio{audio_url:String},
}
pub mod models {
    use serde::{Serialize,Deserialize};
    pub use super::ImageDetail;
    pub const DEFAULT_IMAGE_DETAIL:ImageDetail=ImageDetail::High;
    #[derive(Clone,Serialize,Deserialize)] #[serde(untagged)] pub enum ImageReference { Inline { image_url:String }, File { file_id:String } }
    #[derive(Clone,Serialize,Deserialize)] #[serde(tag="type",rename_all="snake_case")]
    pub enum FunctionCallOutputContentItem {
        InputText{text:String}, InputImage{#[serde(flatten)] image:ImageReference,#[serde(skip_serializing_if="Option::is_none")] detail:Option<ImageDetail>},
        InputAudio{audio_url:String},EncryptedContent{encrypted_content:String},
    }
    pub enum FunctionCallOutputBody { Text(String), ContentItems(Vec<FunctionCallOutputContentItem>) }
    pub struct FunctionCallOutputPayload { pub body:FunctionCallOutputBody }
    pub struct ResponseInputItem { pub body:Vec<FunctionCallOutputContentItem> }
}
pub mod mcp {
    #[derive(Clone,serde::Serialize)]
    #[serde(rename_all="camelCase")]
    pub struct CallToolResult {
        pub content:Vec<serde_json::Value>,pub structured_content:Option<serde_json::Value>,
        pub is_error:Option<bool>,pub meta:Option<serde_json::Value>,
    }
}
pub mod tools { pub mod context {
    use super::super::models::{FunctionCallOutputContentItem,ResponseInputItem};
    pub struct ToolPayload;
    pub trait ToolOutput {
        fn log_output(&self)->String;fn success_for_logging(&self)->bool;
        fn set_handler_duration_ms(&mut self,_ms:u64){}
        fn to_response_item(&self,call_id:&str,payload:&ToolPayload)->ResponseInputItem;
    }
    pub struct FunctionToolOutput {pub body:Vec<FunctionCallOutputContentItem>,pub success:Option<bool>}
    impl FunctionToolOutput {pub fn from_content(body:Vec<FunctionCallOutputContentItem>,success:Option<bool>)->Self{Self{body,success}}}
    impl ToolOutput for FunctionToolOutput {
        fn log_output(&self)->String{String::new()}fn success_for_logging(&self)->bool{self.success.unwrap_or(true)}
        fn to_response_item(&self,_id:&str,_payload:&ToolPayload)->ResponseInputItem{ResponseInputItem{body:self.body.clone()}}
    }
} }
pub enum RuntimeResponse {
    Yielded{cell_id:String,content_items:Vec<FunctionCallOutputContentItem>,host:Option<Duration>},
    Terminated{cell_id:String,content_items:Vec<FunctionCallOutputContentItem>,host:Option<Duration>},
    Result{cell_id:String,content_items:Vec<FunctionCallOutputContentItem>,error_text:Option<String>,host:Option<Duration>},
}
impl RuntimeResponse{pub fn code_mode_host_duration(&self)->Option<Duration>{match self{Self::Yielded{host,..}|Self::Terminated{host,..}|Self::Result{host,..}=>*host}}}
#[path="native-string.rs"] pub mod native_string;
#[path="native-output.rs"] mod native_output;
#[path="native-policy.rs"] pub mod protocol;
#[path="native-header.rs"] mod output;
#[path="native-response-adapter.rs"] mod response_adapter;
#[path="native-image-detail.rs"] mod image_detail;
use crate::openai_models::ModelInfo;
use models::FunctionCallOutputContentItem as OutputItem;
use tools::context::{FunctionToolOutput,ToolOutput,ToolPayload};
use output::CodeModeToolOutput;
use response_adapter::into_function_call_output_content_items;
use image_detail::{can_request_original_image_detail,sanitize_original_image_detail as sanitize_image_detail_items};
use native_output::{formatted_truncate_text_content_items_with_policy,truncate_function_output_items_with_policy};
use protocol::TruncationPolicy;
use OutputItem as FunctionCallOutputContentItemAlias;

// These vectors use invalid audio URLs, whose source estimator uses its string-count fallback.
fn estimate_audio_token_count(value:&str)->usize{native_string::approx_token_count(value)}
mod source_functions {
    use super::*;
    use serde_json::Value as JsonValue;
    use models::ImageReference;
    use FunctionCallOutputContentItemAlias as FunctionCallOutputContentItem;
    include!("native-response-functions.rs");
    include!("native-content-result.rs");
    struct JsonOutputValue { value:JsonValue }
    #[derive(Serialize)] #[serde(transparent)] struct McpOutputValue(JsonValue);
    include!("native-output-value-impls.rs");
    pub(super) fn tool_output(v:&Value)->Value{
        let value=if let Some(payload)=v.get("payload"){
            if v["origin"]=="mcp"{McpOutputValue(payload.clone()).code_mode_result(&ToolPayload)}else{JsonOutputValue{value:payload.clone()}.code_mode_result(&ToolPayload)}
        }else if let Some(content)=v.get("content"){
            let items:Vec<models::FunctionCallOutputContentItem>=serde_json::from_value(content.clone()).unwrap();content_items_to_code_mode_result(&items)
        }else{JsonValue::String(v["output"].as_str().unwrap_or_default().to_string())};
        json!({"value":value,"rejected":v["is_error"].as_bool().unwrap_or(false)&&v["disposition"].as_str().unwrap_or("tool_output")=="reject"})
    }
    pub(super) fn format(v:&Value)->Value{
        let host=v.get("host_duration_ns").filter(|v|!v.is_null()).map(|v|Duration::from_nanos(v.as_u64().unwrap_or_else(||v.as_str().unwrap().parse().unwrap())));
        let content_items:Vec<crate::FunctionCallOutputContentItem>=serde_json::from_value(v.get("content").cloned().unwrap_or(json!([]))).unwrap();
        let cell_id=v["cell_id"].as_str().unwrap_or("fixture").to_string();
        let response=match v["status"].as_str().unwrap_or("result"){
            "yielded"=>RuntimeResponse::Yielded{cell_id,content_items,host},"terminated"=>RuntimeResponse::Terminated{cell_id,content_items,host},
            _=>RuntimeResponse::Result{cell_id,content_items,host,error_text:v["error"].as_str().map(str::to_string)},
        };
        let wall=host.unwrap_or_else(||Duration::from_millis(v["wall_millis"].as_u64().unwrap_or(0)));
        let mut output=handle_runtime_response(&ModelInfo{supports_image_detail_original:v["supports_original"].as_bool().unwrap_or(true)},response,v["max_output_tokens"].as_u64().map(|n|n as usize),wall,v["show_cell_overhead"].as_bool().unwrap_or(false));
        if let Some(duration)=v["handler_duration_millis"].as_u64(){output.set_handler_duration_ms(duration);}
        json!({"content":output.to_response_item("fixture",&ToolPayload).body,"success":output.success_for_logging()})
    }
}
pub fn evaluate(v:&Value)->Value{source_functions::format(v)}
pub fn evaluate_tool_output(v:&Value)->Value{source_functions::tool_output(v)}
