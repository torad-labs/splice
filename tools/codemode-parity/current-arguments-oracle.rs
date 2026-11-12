use serde::Deserialize;
use serde_json::{Value,json};
const DEFAULT_WAIT_YIELD_TIME_MS:u64=10000;
enum FunctionCallError { RespondToModel(String) }
include!("native-wait-arguments.rs");
pub fn evaluate(input:&Value)->Value {
    if input["operation"]=="exec_arguments" {
        match crate::description::parse_exec_source(input["source"].as_str().unwrap_or_default()) {
            Ok(args)=>json!({"source":args.code,"cell_id":null,"terminate":false,
                "yield_time_ms":args.yield_time_ms.unwrap_or_else(||input["default_yield_time_ms"].as_u64().or_else(||input["default_yield_time_ms"].as_str().and_then(|v|v.parse().ok())).unwrap_or(30000)).to_string(),
                "max_output_tokens":args.max_output_tokens.unwrap_or(10000).to_string()}),
            Err(error)=>json!({"error":error}),
        }
    } else {
        match parse_arguments::<ExecWaitArgs>(input["arguments"].as_str().unwrap_or_default()) {
            Ok(args)=>json!({"source":null,"cell_id":args.cell_id,"terminate":args.terminate,"yield_time_ms":args.yield_time_ms.to_string(),"max_output_tokens":args.max_tokens.unwrap_or(10000).to_string()}),
            Err(FunctionCallError::RespondToModel(error))=>json!({"error":error}),
        }
    }
}
