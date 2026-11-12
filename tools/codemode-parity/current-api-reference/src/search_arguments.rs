use codex_protocol::models::SearchToolCallParams;
use codex_tools::{FunctionCallError,TOOL_SEARCH_DEFAULT_LIMIT};
pub fn evaluate(v:&serde_json::Value)->serde_json::Value {
 let parsed=crate::parse_arguments::parse_arguments::<SearchToolCallParams>(v["arguments"].as_str().unwrap_or(""));
 let result=parsed.and_then(|args| {
        let query = args.query.trim();
        if query.is_empty() {
            return Err(FunctionCallError::RespondToModel(
                "query must not be empty".to_string(),
            ));
        }
        let limit = args.limit.unwrap_or(TOOL_SEARCH_DEFAULT_LIMIT);

        if limit == 0 {
            return Err(FunctionCallError::RespondToModel(
                "limit must be greater than zero".to_string(),
            ));
        }

 Ok((query.to_owned(),limit))
 });
 match result { Ok((query,limit))=>serde_json::json!({"query":query,"limit":limit.to_string()}),Err(error)=>serde_json::json!({"error":error.to_string()}) }
}
