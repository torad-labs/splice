#[path = "../../source/codex-rs/core/src/code_mode_host.rs"]
mod actual_local_provider;

use std::collections::HashMap;
use std::io::Read;
use std::process::Stdio;
use std::sync::{Arc, Mutex};
use std::time::Duration;

use codex_code_mode::{
    CellId, CodeModeNestedToolCall, CodeModeSession, CodeModeSessionCellExecutionLimits,
    CodeModeSessionDelegate, CodeModeSessionProvider, ExecuteRequest, GrpcCodeModeSessionProvider,
    NotificationFuture, StartedCell, ToolInvocationFuture, WaitRequest,
};
use serde_json::{json, Value};
use tokio::net::TcpListener;
use tokio::process::{Child, Command};
use tokio_util::sync::CancellationToken;

type Events = Arc<Mutex<Vec<Value>>>;

fn record(events: &Events, event: Value) {
    events.lock().unwrap().push(event);
}

fn unsigned(value: &Value, name: &str) -> Option<u64> {
    value.get(name).and_then(|v| v.as_u64().or_else(|| v.as_str()?.parse().ok()))
}

fn response<T: serde::Serialize>(result: Result<T, String>) -> Value {
    match result {
        Ok(value) => json!({"ok": value}),
        Err(error) => json!({"error": error}),
    }
}

struct Delegate {
    events: Events,
    config: Value,
}

impl CodeModeSessionDelegate for Delegate {
    fn invoke_tool<'a>(
        &'a self,
        invocation: CodeModeNestedToolCall,
        cancellation: CancellationToken,
    ) -> ToolInvocationFuture<'a> {
        record(&self.events, json!({"type": "tool", "session":self.config.get("session").and_then(Value::as_str).unwrap_or("main"),"cell_alias":self.config["as"].as_str().unwrap_or("last"), "input_present":invocation.input.is_some(), "value": invocation}));
        Box::pin(async move {
            let tool_name = serde_json::to_value(&invocation.tool_name).map_err(|e|e.to_string())?;
            let member = tool_name["name"].as_str().unwrap_or("");
            let config = self.config.get("callbacks").and_then(|v|v.get(member)).unwrap_or(&self.config);
            let delay = unsigned(config, "callback_delay_ms").unwrap_or(0);
            tokio::select! {
                _ = cancellation.cancelled() => {
                    record(&self.events, json!({"type":"tool_cancelled", "runtime_tool_call_id": invocation.runtime_tool_call_id}));
                    Err("witness delegate cancelled".to_string())
                }
                _ = tokio::time::sleep(Duration::from_millis(delay)) => {
                    if let Some(message) = config.get("callback_error").and_then(Value::as_str) {
                        Err(message.to_string())
                    } else {
                        Ok(config.get("callback_result").cloned().unwrap_or_else(|| invocation.input.unwrap_or_else(|| json!({}))))
                    }
                }
            }
        })
    }

    fn notify<'a>(
        &'a self,
        call_id: String,
        cell_id: CellId,
        text: String,
        cancellation: CancellationToken,
    ) -> NotificationFuture<'a> {
        record(&self.events, json!({"type":"notification", "session":self.config.get("session").and_then(Value::as_str).unwrap_or("main"),"cell_alias":self.config["as"].as_str().unwrap_or("last"), "call_id":call_id, "cell_id":cell_id, "text":text}));
        Box::pin(async move {
            let delay = unsigned(&self.config, "notification_delay_ms").unwrap_or(0);
            tokio::select! {
                _ = cancellation.cancelled() => {
                    record(&self.events, json!({"type":"notification_cancelled", "cell_id":cell_id}));
                    Err("witness notification cancelled".to_string())
                }
                _ = tokio::time::sleep(Duration::from_millis(delay)) => {
                    record(&self.events, json!({"type":"notification_delivery_finished", "session":self.config.get("session").and_then(Value::as_str).unwrap_or("main"), "cell_alias":self.config["as"].as_str().unwrap_or("last"), "cell_id":cell_id}));
                    Ok(())
                }
            }
        })
    }

    fn cell_closed(&self, cell_id: &CellId) {
        record(&self.events, json!({"type":"cell_closed", "cell_id":cell_id}));
    }
}

struct Host {
    child: Child,
    bridge: tokio::task::JoinHandle<()>,
    endpoint: String,
}

impl Host {
    async fn start(binary: &str) -> Result<Self, String> {
        let listener = TcpListener::bind("127.0.0.1:0").await.map_err(|e| e.to_string())?;
        let endpoint = format!("http://{}", listener.local_addr().map_err(|e| e.to_string())?);
        let mut child = Command::new(binary)
            .args(["--listen", "grpc+stdio://"])
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::inherit())
            .kill_on_drop(true)
            .spawn().map_err(|e| e.to_string())?;
        let mut host_stdin = child.stdin.take().ok_or("missing host stdin")?;
        let mut host_stdout = child.stdout.take().ok_or("missing host stdout")?;
        // This bridge only copies bytes. Every session/RPC/state decision belongs to the official crate.
        let bridge = tokio::spawn(async move {
            if let Ok((socket, _)) = listener.accept().await {
                let _ = socket.set_nodelay(true);
                let (mut read, mut write) = socket.into_split();
                let _ = tokio::try_join!(
                    tokio::io::copy(&mut read, &mut host_stdin),
                    tokio::io::copy(&mut host_stdout, &mut write),
                );
            }
        });
        Ok(Self { child, bridge, endpoint })
    }
}

impl Drop for Host {
    fn drop(&mut self) {
        self.bridge.abort();
        let _ = self.child.start_kill();
    }
}

struct Harness {
    provider: Box<dyn CodeModeSessionProvider>,
    sessions: HashMap<String, Arc<dyn CodeModeSession>>,
    cells: HashMap<String, CellId>,
    initial: HashMap<String, StartedCell>,
    events: Events,
    host: Option<Host>,
    public_options: bool,
}

impl Harness {
    fn cell(&self, step: &Value) -> CellId {
        let alias = step.get("cell").and_then(Value::as_str).unwrap_or("last");
        self.cells.get(alias).cloned().unwrap_or_else(|| CellId::new(alias.to_string()))
    }

    async fn open(&mut self, name: &str, config: &Value) -> Value {
        let opening = async { if config.get("limits").is_some() {
            let limits = &config["limits"];
            self.provider.create_session_with_limits(CodeModeSessionCellExecutionLimits {
                max_yield_time_ms: unsigned(limits, "max_yield_time_ms"),
                max_heap_size_bytes: unsigned(limits, "max_heap_size_bytes").map(|n| n as usize),
            }).await
        } else {
            self.provider.create_session().await
        } };
        let result = if let Some(ms) = unsigned(config,"cancel_after_ms") {
            match tokio::time::timeout(Duration::from_millis(ms),opening).await { Ok(value)=>value, Err(_)=>return json!({"cancelled":true}) }
        } else { opening.await };
        match result {
            Ok(session) => {
                self.sessions.insert(name.to_string(), session);
                json!({"ok":true})
            }
            Err(error) => json!({"error":error}),
        }
    }

    async fn shutdown_queued(&self, step: &Value, session: Arc<dyn CodeModeSession>) -> Result<Value, String> {
        let mut jobs = Vec::new();
        for index in 0..2 {
            let session = session.clone();
            let events = self.events.clone();
            let config = step.clone();
            jobs.push(tokio::spawn(async move {
                let request: ExecuteRequest = serde_json::from_value(json!({"tool_call_id":format!("queued-{index}"),"enabled_tools":[],"source":"scripted queued request","yield_time_ms":1})).map_err(|e|e.to_string())?;
                let delegate = Arc::new(Delegate { events, config });
                match session.execute(request,delegate,None).await {
                    Ok(started) => { drop(started); Ok::<Value,String>(json!({"unexpected_started":true})) },
                    Err(error) => Ok::<Value,String>(json!({"error":error})),
                }
            }));
        }
        tokio::time::sleep(Duration::from_millis(80)).await;
        let shutdown = response(session.shutdown().await);
        let mut queued = Vec::new();
        for job in jobs { queued.push(job.await.map_err(|e|e.to_string())??); }
        Ok(json!({"ok":{"shutdown":shutdown,"queued":queued}}))
    }

    async fn parallel_wait(&self, step: &Value, session: Arc<dyn CodeModeSession>) -> Result<Value, String> {
        let cell = self.cell(step);
        let first_session = session.clone();
        let first_cell = cell.clone();
        let first = tokio::spawn(async move {
            first_session.wait(WaitRequest { cell_id:first_cell, yield_time_ms:10_000 },None).await
        });
        tokio::time::sleep(Duration::from_millis(30)).await;
        let overlapping = response(session.wait(WaitRequest { cell_id:cell.clone(), yield_time_ms:10_000 },None).await);
        first.abort();
        let _ = first.await;
        let replacement_session = session.clone();
        let replacement_cell = cell.clone();
        let replacement = tokio::spawn(async move {
            replacement_session.wait(WaitRequest { cell_id:replacement_cell, yield_time_ms:10_000 },None).await
        });
        tokio::time::sleep(Duration::from_millis(30)).await;
        let queued_overlap = response(session.wait(WaitRequest { cell_id:cell, yield_time_ms:10_000 },None).await);
        let replaced = response(replacement.await.map_err(|e|e.to_string())?);
        Ok(json!({"ok":{"overlapping":overlapping,"queued_overlap":queued_overlap,"replacement":replaced}}))
    }

    async fn step(&mut self, step: &Value) -> Result<Value, String> {
        let op = step["op"].as_str().ok_or("step omitted op")?;
        let session_name = step.get("session").and_then(Value::as_str).unwrap_or("main");
        if op == "open" { return Ok(self.open(session_name, step).await); }
        if op == "sleep" {
            tokio::time::sleep(Duration::from_millis(unsigned(step,"ms").unwrap_or(0))).await;
            return Ok(json!({"ok":true}));
        }
        if op == "crash_host" || op == "pause_host" {
            if let Some(host) = self.host.as_mut() {
                host.child.kill().await.map_err(|e|e.to_string())?;
            } else {
                let file = std::env::var("SPLICE_HOST_PID_FILE").map_err(|e|e.to_string())?;
                let pid = std::fs::read_to_string(file).map_err(|e|e.to_string())?;
                let status = Command::new("kill").args([if op == "pause_host" { "-STOP" } else { "-KILL" },pid.trim()]).status().await.map_err(|e|e.to_string())?;
                if !status.success() { return Err("could not crash the current witness host".into()); }
            }
            return Ok(json!({"ok":true}));
        }
        let session = self.sessions.get(session_name).cloned().ok_or("unknown witness session")?;
        let token = unsigned(step, "preempt_after_ms").map(|ms| {
            let token = CancellationToken::new();
            let trigger = token.clone();
            tokio::spawn(async move {
                tokio::time::sleep(Duration::from_millis(ms)).await;
                trigger.cancel();
            });
            token
        });
        match op {
            "execute" => {
                let mut request: ExecuteRequest = serde_json::from_value(json!({
                    "tool_call_id":step.get("tool_call_id").cloned().unwrap_or(json!("witness-call")),
                    "enabled_tools":step.get("enabled_tools").cloned().unwrap_or(json!([])),
                    "source":step["source"],
                    "yield_time_ms":unsigned(step,"yield_time_ms").or(self.public_options.then_some(10_000)),
                    "max_output_tokens":unsigned(step,"max_output_tokens").or(self.public_options.then_some(10_000)),
                })).map_err(|e|e.to_string())?;
                // A supplied JSON null is Some(JsonNull) in the public Rust API; omission is None.
                if let Some(tools) = step.get("enabled_tools").and_then(Value::as_array) {
                    for (definition, input) in request.enabled_tools.iter_mut().zip(tools) {
                        if let Some(schema) = input.get("input_schema") { definition.input_schema = Some(schema.clone()); }
                        if let Some(schema) = input.get("output_schema") { definition.output_schema = Some(schema.clone()); }
                    }
                }
                let delegate = Arc::new(Delegate { events:self.events.clone(), config:step.clone() });
                let future = session.execute(request,delegate,token);
                let accepted = if let Some(ms) = unsigned(step,"cancel_after_ms") { match tokio::time::timeout(Duration::from_millis(ms),future).await { Ok(value)=>value,Err(_)=>return Ok(json!({"cancelled":true})), } } else { future.await };
                let started = match accepted {
                    Ok(value) => value,
                    Err(error) => return Ok(json!({"error":error})),
                };
                let alias = step.get("as").and_then(Value::as_str).unwrap_or("last").to_string();
                self.cells.insert("last".to_string(),started.cell_id.clone());
                self.cells.insert(alias.clone(),started.cell_id.clone());
                let cell_id = started.cell_id.clone();
                record(&self.events,json!({"type":"started", "session":session_name, "alias":alias,"cell_id":cell_id}));
                if step["defer_initial"].as_bool().unwrap_or(false) {
                    self.initial.insert(alias,started);
                    Ok(json!({"ok":{"started":cell_id}}))
                } else if step["drop_initial"].as_bool().unwrap_or(false) {
                    drop(started);
                    Ok(json!({"ok":{"dropped_initial":cell_id}}))
                } else {
                    Ok(response(started.initial_response().await))
                }
            }
            "initial" => {
                let alias = step.get("cell").and_then(Value::as_str).unwrap_or("last");
                let initial = self.initial.remove(alias).ok_or("unknown deferred initial response")?;
                Ok(response(initial.initial_response().await))
            }
            "parallel_wait" => self.parallel_wait(step,session).await,
            "shutdown_queued" => self.shutdown_queued(step,session).await,
            "wait" => {
                let wait = session.wait(WaitRequest {
                    cell_id:self.cell(step), yield_time_ms:unsigned(step,"yield_time_ms").unwrap_or(10_000),
                },token);
                if let Some(ms) = unsigned(step,"cancel_after_ms") {
                    match tokio::time::timeout(Duration::from_millis(ms),wait).await {
                        Ok(result) => Ok(response(result)),
                        Err(_) => Ok(json!({"cancelled":true})),
                    }
                } else { Ok(response(wait.await)) }
            }
            "terminate" => Ok(response(session.terminate(self.cell(step)).await)),
            "shutdown" => Ok(response(session.shutdown().await)),
            _ => Err(format!("unknown witness op: {op}")),
        }
    }
}

async fn run(input: &Value, binary: &str) -> Result<Value, String> {
    let events: Events = Arc::new(Mutex::new(Vec::new()));
    let (provider,host): (Box<dyn CodeModeSessionProvider>,Option<Host>) = if input["provider"].as_str() == Some("process") {
        (Box::new(actual_local_provider::ProcessOwnedGrpcCodeModeSessionProvider::with_host_program(binary.into())),None)
    } else {
        let host = Host::start(binary).await?;
        let provider = if input["provider"].as_str() == Some("channel") {
            let channel = tonic::transport::Endpoint::from_shared(host.endpoint.clone()).map_err(|error|error.to_string())?.connect_lazy();
            GrpcCodeModeSessionProvider::with_channel(channel)
        } else {
            GrpcCodeModeSessionProvider::new(host.endpoint.clone())
        };
        (Box::new(provider),Some(host))
    };
    let mut harness = Harness {
        provider,
        sessions:HashMap::new(), cells:HashMap::new(), initial:HashMap::new(), events:events.clone(),host,public_options:input["adapter"].as_str().is_some_and(|adapter|adapter!="connection"),
    };
    let mut results = Vec::new();
    if !input["manual_open"].as_bool().unwrap_or(false) {
        results.push(json!({"op":"open","result":harness.open("main",input).await}));
    }
    for (index, step) in input["steps"].as_array().ok_or("case omitted steps")?.iter().enumerate() {
        let result = harness.step(step).await?;
        record(&events,json!({"type":"operation_returned","step_index":index,"op":step["op"]}));
        results.push(json!({"op":step["op"],"result":result}));
    }
    for session in harness.sessions.values() { let _ = session.shutdown().await; }
    Ok(json!({"steps":results,"events":events.lock().unwrap().clone()}))
}

#[tokio::main(flavor="current_thread")]
async fn main() {
    let binary = std::env::args().nth(1).expect("supply the frozen official host path");
    let mut body = String::new();
    std::io::stdin().read_to_string(&mut body).unwrap();
    let input:Value = serde_json::from_str(&body).unwrap();
    let result = match tokio::time::timeout(Duration::from_millis(unsigned(&input,"case_timeout_ms").unwrap_or(30_000).min(90_000)),run(&input,&binary)).await {
        Ok(Ok(result)) => result,
        Ok(Err(error)) => json!({"harness_error":error}),
        Err(_) => json!({"harness_error":"case exceeded 30 seconds"}),
    };
    println!("{}",json!({"id":input["id"],"nonce":input["nonce"],"result":result}));
}
