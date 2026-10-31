// Native audio estimation/cache/string implementations are unmodified upstream
// source. Only unused protocol model shapes are supplied locally.
extern crate self as codex_protocol;
extern crate self as codex_utils_cache;
extern crate self as codex_utils_string;

#[path = "native-string.rs"] mod native_string;
#[path = "native-cache.rs"] mod native_cache;
#[path = "native-audio.rs"] mod native_audio;
pub use native_string::*;
pub use native_cache::*;

pub mod models {
    pub const MAX_PROMPT_AUDIO_INPUT_BYTES: usize = 50 * 1024 * 1024;
    pub enum ContentItem { InputAudio { audio_url: String }, InputText { text: String } }
    pub enum FunctionCallOutputContentItem { InputAudio { audio_url: String }, InputText { text: String } }
    pub struct FunctionCallOutputPayload { pub content: Vec<FunctionCallOutputContentItem> }
    impl FunctionCallOutputPayload {
        pub fn content_items_mut(&mut self) -> Option<&mut Vec<FunctionCallOutputContentItem>> { Some(&mut self.content) }
    }
    pub enum ResponseItem {
        Message { content: Vec<ContentItem> },
        FunctionCallOutput { output: FunctionCallOutputPayload },
        CustomToolCallOutput { output: FunctionCallOutputPayload },
        AdditionalTools {}, Reasoning {}, AgentMessage {}, LocalShellCall {}, FunctionCall {}, ToolSearchCall {},
        CustomToolCall {}, ToolSearchOutput {}, WebSearchCall {}, ImageGenerationCall {}, Compaction {},
        ConfigurationUpdate {}, CompactionTrigger {}, ContextCompaction {}, Other,
    }
}

fn main() {
    use std::io::BufRead;
    for line in std::io::stdin().lock().lines() {
        let line = line.unwrap();
        let (index, hex) = line.split_once('\t').unwrap();
        let bytes = (0..hex.len()).step_by(2).map(|at| u8::from_str_radix(&hex[at..at + 2], 16).unwrap()).collect();
        let url = String::from_utf8(bytes).unwrap();
        println!("{index}\t{}", native_audio::estimate_audio_token_count(&url));
    }
}
