// Only protocol DATA TYPES are shimmed. Both algorithms and the budget policy
// are copied byte-for-byte from the pinned upstream Git revision by the runner.
extern crate self as codex_protocol;
extern crate self as codex_utils_string;

#[path = "native-string.rs"] mod native_string;
pub use native_string::*;
#[path = "native-output.rs"] mod native_output;
#[path = "native-policy.rs"] pub mod protocol;

pub mod models {
    #[derive(Clone, Copy)]
    pub enum ImageDetail { Auto, Low, High, Original }
    impl ImageDetail {
        pub fn parse(value: &str) -> Self {
            match value { "auto" => Self::Auto, "low" => Self::Low, "high" => Self::High, "original" => Self::Original, _ => panic!("invalid detail") }
        }
        pub fn as_str(self) -> &'static str {
            match self { Self::Auto => "auto", Self::Low => "low", Self::High => "high", Self::Original => "original" }
        }
    }
    #[derive(Clone)]
    pub enum FunctionCallOutputContentItem {
        InputText { text: String },
        InputImage { image: String, detail: Option<ImageDetail> },
        InputAudio { audio_url: String },
        EncryptedContent { encrypted_content: String },
    }
    pub enum FunctionCallOutputBody { Text(String), ContentItems(Vec<FunctionCallOutputContentItem>) }
    pub struct FunctionCallOutputPayload { pub body: FunctionCallOutputBody }
}

fn unhex(value: &str) -> String {
    let bytes = (0..value.len()).step_by(2).map(|at| u8::from_str_radix(&value[at..at + 2], 16).unwrap()).collect();
    String::from_utf8(bytes).unwrap()
}
fn hex(value: &str) -> String { value.as_bytes().iter().map(|b| format!("{b:02x}")).collect() }

fn main() {
    use std::io::BufRead;
    use models::FunctionCallOutputContentItem::*;
    for line in std::io::stdin().lock().lines() {
        let line = line.unwrap();
        let fields: Vec<_> = line.split('\t').collect();
        let index: usize = fields[0].parse().unwrap();
        let budget: usize = fields[1].parse().unwrap();
        let mut items = Vec::new();
        for field in &fields[2..] {
            let data: Vec<_> = field.split(':').collect();
            let value = unhex(data[1]);
            items.push(match data[0] {
                "t" => InputText { text: value },
                "i" => InputImage { image: value, detail: data.get(2).filter(|v| !v.is_empty()).map(|v| models::ImageDetail::parse(&unhex(v))) },
                "a" => InputAudio { audio_url: value },
                "e" => EncryptedContent { encrypted_content: value },
                _ => panic!("unknown native oracle content type"),
            });
        }
        let policy = protocol::TruncationPolicy::Tokens(budget);
        let (content, truncated) = if items.iter().all(|item| matches!(item, InputText { .. })) {
            native_output::formatted_truncate_text_content_items_with_policy(&items, policy)
        } else {
            // These fixtures deliberately contain malformed audio data URLs, so
            // Codex's duration estimator takes its documented byte-count fallback.
            (native_output::truncate_function_output_items_with_policy(&items, policy, approx_token_count), None)
        };
        println!("{index}\ttruncated\t{}", truncated.map(|v| v.to_string()).unwrap_or_default());
        for item in content {
            match item {
                InputText { text } => println!("{index}\tt\t{}", hex(&text)),
                InputImage { image, detail } => println!("{index}\ti\t{}\t{}", hex(&image), detail.map(|v| hex(v.as_str())).unwrap_or_default()),
                InputAudio { audio_url } => println!("{index}\ta\t{}", hex(&audio_url)),
                EncryptedContent { encrypted_content } => println!("{index}\te\t{}", hex(&encrypted_content)),
            }
        }
    }
}
