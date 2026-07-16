"""
Generate an HTML review viewer for skill eval results.

Usage:
    python eval-viewer/generate_review.py <workspace>/iteration-N \\
      --skill-name "my-skill" \\
      [--benchmark <workspace>/iteration-N/benchmark.json] \\
      [--previous-workspace <workspace>/iteration-N-1] \\
      [--static <output_path.html>]

The viewer has two tabs:
  - Outputs: click through each test case, leave feedback
  - Benchmark: quantitative comparison stats

When --static is NOT given: starts a local HTTP server and opens the browser.
When --static IS given: writes a standalone HTML file (for headless/Cowork environments).

Feedback is submitted via "Submit All Reviews" which saves feedback.json to the
iteration directory (or downloads it in static mode).
"""

from __future__ import annotations

import argparse
import http.server
import json
import os
import sys
import threading
import webbrowser
from pathlib import Path
from typing import Optional


def find_eval_dirs(iteration_dir: Path) -> list[Path]:
    return sorted([d for d in iteration_dir.iterdir() if d.is_dir() and d.name.startswith("eval-")])


def load_json(path: Path) -> Optional[dict]:
    if path.exists():
        with open(path) as f:
            return json.load(f)
    return None


def read_output_files(outputs_dir: Path) -> list:
    """Read all output files from a run's outputs/ directory."""
    files = []
    if not outputs_dir.is_dir():
        return files
    for f in sorted(outputs_dir.iterdir()):
        if f.is_file():
            content = None
            file_type = "text"
            try:
                content = f.read_text(encoding="utf-8", errors="replace")
                if f.suffix in (".json",):
                    file_type = "json"
                elif f.suffix in (".md", ".markdown"):
                    file_type = "markdown"
                elif f.suffix in (".html", ".htm"):
                    file_type = "html"
                elif f.suffix in (".py", ".ts", ".js", ".sh", ".bash"):
                    file_type = "code"
            except Exception:
                content = f"[binary file: {f.name}]"
                file_type = "binary"
            files.append({"name": f.name, "content": content or "", "type": file_type})
    return files


def collect_eval_data(
    iteration_dir: Path,
    previous_dir: Optional[Path] = None,
) -> list:
    """Collect all data for the Outputs tab."""
    eval_dirs = find_eval_dirs(iteration_dir)
    evals = []

    for eval_dir in eval_dirs:
        metadata = load_json(eval_dir / "eval_metadata.json") or {}
        eval_name = metadata.get("eval_name", eval_dir.name.removeprefix("eval-"))
        prompt = metadata.get("prompt", "")
        assertions = metadata.get("assertions", [])

        # Detect configurations present
        configs = []
        for config_name in ["with_skill", "without_skill", "old_skill"]:
            config_dir = eval_dir / config_name
            if not config_dir.is_dir():
                continue
            outputs = read_output_files(config_dir / "outputs")
            grading = load_json(config_dir / "grading.json")
            display_name = {
                "with_skill": "With Skill",
                "without_skill": "Without Skill",
                "old_skill": "Old Skill",
            }.get(config_name, config_name)
            configs.append({
                "name": config_name,
                "display_name": display_name,
                "outputs": outputs,
                "grading": grading,
            })

        # Previous iteration output (for comparison)
        prev_outputs = []
        if previous_dir:
            prev_eval_dir = previous_dir / eval_dir.name
            prev_with_skill = prev_eval_dir / "with_skill"
            if prev_with_skill.is_dir():
                prev_outputs = read_output_files(prev_with_skill / "outputs")

        evals.append({
            "id": metadata.get("eval_id", eval_dir.name),
            "name": eval_name,
            "dir": eval_dir.name,
            "prompt": prompt,
            "assertions": assertions,
            "configs": configs,
            "previous_outputs": prev_outputs,
        })

    return evals


def generate_html(
    skill_name: str,
    evals: list,
    benchmark: Optional[dict],
    iteration_dir: Path,
    static: bool = False,
) -> str:
    evals_json = json.dumps(evals, ensure_ascii=False)
    benchmark_json = json.dumps(benchmark or {}, ensure_ascii=False)
    feedback_endpoint = "download" if static else "/submit_feedback"

    return f"""<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>Skill Review — {skill_name}</title>
<style>
  * {{ box-sizing: border-box; margin: 0; padding: 0; }}
  body {{ font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif; background: #f5f5f5; color: #1a1a1a; }}
  .header {{ background: #1a1a1a; color: white; padding: 16px 24px; display: flex; align-items: center; gap: 16px; }}
  .header h1 {{ font-size: 18px; font-weight: 600; }}
  .header .skill-badge {{ background: #444; padding: 4px 10px; border-radius: 4px; font-size: 13px; }}
  .tabs {{ display: flex; gap: 0; border-bottom: 1px solid #ddd; background: white; padding: 0 24px; }}
  .tab {{ padding: 12px 20px; cursor: pointer; border-bottom: 2px solid transparent; font-size: 14px; color: #666; }}
  .tab.active {{ border-bottom-color: #0070f3; color: #0070f3; font-weight: 500; }}
  .tab-content {{ display: none; }}
  .tab-content.active {{ display: block; }}

  /* Outputs tab */
  .eval-nav {{ background: white; border-bottom: 1px solid #eee; padding: 12px 24px; display: flex; align-items: center; gap: 12px; }}
  .eval-nav button {{ padding: 6px 14px; border: 1px solid #ddd; border-radius: 4px; background: white; cursor: pointer; font-size: 13px; }}
  .eval-nav button:hover {{ background: #f5f5f5; }}
  .eval-nav .eval-counter {{ font-size: 13px; color: #666; }}
  .eval-name {{ font-weight: 600; font-size: 15px; }}
  .eval-container {{ max-width: 1200px; margin: 24px auto; padding: 0 24px; }}
  .card {{ background: white; border: 1px solid #e5e5e5; border-radius: 8px; margin-bottom: 16px; overflow: hidden; }}
  .card-header {{ padding: 12px 16px; background: #fafafa; border-bottom: 1px solid #e5e5e5; font-weight: 600; font-size: 13px; color: #444; display: flex; justify-content: space-between; align-items: center; }}
  .card-body {{ padding: 16px; }}
  .prompt {{ font-size: 14px; line-height: 1.5; white-space: pre-wrap; }}
  .output-file {{ margin-bottom: 12px; }}
  .output-file .filename {{ font-size: 12px; color: #666; margin-bottom: 4px; font-family: monospace; }}
  .output-file pre {{ background: #f8f8f8; border: 1px solid #e5e5e5; border-radius: 4px; padding: 12px; font-size: 12px; overflow-x: auto; max-height: 400px; overflow-y: auto; white-space: pre-wrap; word-break: break-word; }}
  .grading-row {{ display: flex; align-items: flex-start; gap: 8px; padding: 6px 0; border-bottom: 1px solid #f0f0f0; font-size: 13px; }}
  .grading-row:last-child {{ border-bottom: none; }}
  .pass-badge {{ padding: 2px 8px; border-radius: 3px; font-size: 11px; font-weight: 600; white-space: nowrap; }}
  .pass-badge.pass {{ background: #dcfce7; color: #166534; }}
  .pass-badge.fail {{ background: #fee2e2; color: #991b1b; }}
  .evidence {{ color: #666; font-size: 12px; margin-top: 2px; }}
  .feedback-area textarea {{ width: 100%; padding: 10px; border: 1px solid #ddd; border-radius: 4px; font-size: 14px; resize: vertical; min-height: 80px; font-family: inherit; }}
  .feedback-area textarea:focus {{ outline: none; border-color: #0070f3; }}
  .collapsible-header {{ cursor: pointer; user-select: none; }}
  .collapsible-header::after {{ content: ' ▼'; font-size: 10px; }}
  .collapsible-header.collapsed::after {{ content: ' ▶'; }}
  .collapsible-body {{ }}
  .collapsible-body.hidden {{ display: none; }}
  .config-tabs {{ display: flex; gap: 4px; margin-bottom: 12px; }}
  .config-tab {{ padding: 6px 12px; border: 1px solid #ddd; border-radius: 4px; cursor: pointer; font-size: 12px; background: white; }}
  .config-tab.active {{ background: #0070f3; color: white; border-color: #0070f3; }}
  .config-panel {{ display: none; }}
  .config-panel.active {{ display: block; }}

  /* Benchmark tab */
  .benchmark-container {{ max-width: 900px; margin: 24px auto; padding: 0 24px; }}
  table {{ width: 100%; border-collapse: collapse; font-size: 13px; }}
  th {{ background: #fafafa; padding: 10px 12px; text-align: left; border-bottom: 2px solid #e5e5e5; font-weight: 600; }}
  td {{ padding: 10px 12px; border-bottom: 1px solid #f0f0f0; }}
  .delta-positive {{ color: #166534; font-weight: 500; }}
  .delta-negative {{ color: #991b1b; font-weight: 500; }}
  .analyst-notes {{ background: #fffbeb; border: 1px solid #fcd34d; border-radius: 6px; padding: 16px; margin-top: 16px; font-size: 13px; line-height: 1.6; }}
  .analyst-notes pre {{ white-space: pre-wrap; font-family: inherit; }}

  /* Submit bar */
  .submit-bar {{ position: fixed; bottom: 0; left: 0; right: 0; background: white; border-top: 1px solid #e5e5e5; padding: 12px 24px; display: flex; justify-content: flex-end; gap: 12px; }}
  .btn-submit {{ background: #0070f3; color: white; border: none; padding: 10px 20px; border-radius: 6px; cursor: pointer; font-size: 14px; font-weight: 500; }}
  .btn-submit:hover {{ background: #0051b3; }}
  .saved-indicator {{ font-size: 12px; color: #666; align-self: center; }}

  .no-evals {{ text-align: center; padding: 60px; color: #666; }}
</style>
</head>
<body>

<div class="header">
  <h1>Skill Eval Review</h1>
  <span class="skill-badge">{skill_name}</span>
</div>

<div class="tabs">
  <div class="tab active" onclick="switchTab('outputs')">Outputs</div>
  <div class="tab" onclick="switchTab('benchmark')">Benchmark</div>
</div>

<div id="tab-outputs" class="tab-content active">
  <div class="eval-nav" id="evalNav">
    <button onclick="navigate(-1)">← Prev</button>
    <span class="eval-counter" id="evalCounter">1 / ?</span>
    <button onclick="navigate(1)">Next →</button>
    <span class="eval-name" id="evalName"></span>
  </div>
  <div class="eval-container" id="evalContainer"></div>
</div>

<div id="tab-benchmark" class="tab-content">
  <div class="benchmark-container" id="benchmarkContainer"></div>
</div>

<div class="submit-bar">
  <span class="saved-indicator" id="savedIndicator"></span>
  <button class="btn-submit" onclick="submitAll()">Submit All Reviews</button>
</div>

<script>
const EVALS = {evals_json};
const BENCHMARK = {benchmark_json};
const FEEDBACK_ENDPOINT = "{feedback_endpoint}";
const ITERATION_DIR = {json.dumps(str(iteration_dir))};

let currentIndex = 0;
const feedback = {{}};
let autoSaveTimer = null;

// Initialize feedback keys
EVALS.forEach(e => {{
  e.configs.forEach(cfg => {{
    const key = `${{e.dir}}-${{cfg.name}}`;
    feedback[key] = '';
  }});
}});

function switchTab(name) {{
  document.querySelectorAll('.tab').forEach((t, i) => {{
    t.classList.toggle('active', ['outputs', 'benchmark'][i] === name);
  }});
  document.querySelectorAll('.tab-content').forEach(tc => {{
    tc.classList.toggle('active', tc.id === `tab-${{name}}`);
  }});
  if (name === 'benchmark') renderBenchmark();
}}

function navigate(dir) {{
  currentIndex = Math.max(0, Math.min(EVALS.length - 1, currentIndex + dir));
  renderEval(currentIndex);
}}

document.addEventListener('keydown', e => {{
  if (e.target.tagName === 'TEXTAREA') return;
  if (e.key === 'ArrowLeft') navigate(-1);
  if (e.key === 'ArrowRight') navigate(1);
}});

function renderEval(idx) {{
  if (EVALS.length === 0) {{
    document.getElementById('evalContainer').innerHTML = '<div class="no-evals">No eval results found.</div>';
    return;
  }}
  const ev = EVALS[idx];
  document.getElementById('evalCounter').textContent = `${{idx + 1}} / ${{EVALS.length}}`;
  document.getElementById('evalName').textContent = ev.name;

  const container = document.getElementById('evalContainer');
  let html = '';

  // Prompt card
  html += `<div class="card">
    <div class="card-header">Prompt</div>
    <div class="card-body"><div class="prompt">${{escHtml(ev.prompt)}}</div></div>
  </div>`;

  // Output card with config tabs
  if (ev.configs.length > 0) {{
    const configTabsHtml = ev.configs.map((cfg, i) =>
      `<div class="config-tab ${{i === 0 ? 'active' : ''}}" onclick="switchConfig(this, '${{ev.dir}}', ${{i}})">${{cfg.display_name}}</div>`
    ).join('');

    const configPanelsHtml = ev.configs.map((cfg, i) => {{
      let outputHtml = '';
      if (cfg.outputs.length === 0) {{
        outputHtml = '<p style="color:#999;font-size:13px;">No output files found.</p>';
      }} else {{
        cfg.outputs.forEach(f => {{
          outputHtml += `<div class="output-file">
            <div class="filename">${{escHtml(f.name)}}</div>
            <pre>${{escHtml(f.content)}}</pre>
          </div>`;
        }});
      }}

      let gradingHtml = '';
      if (cfg.grading && cfg.grading.expectations && cfg.grading.expectations.length > 0) {{
        const passRate = cfg.grading.expectations.filter(e => e.passed).length / cfg.grading.expectations.length;
        gradingHtml = `
          <div class="card" style="margin-top:12px;">
            <div class="card-header collapsible-header collapsed" onclick="toggleCollapse(this)">
              Assertions (${{Math.round(passRate * 100)}}% passed)
            </div>
            <div class="card-body collapsible-body hidden">
              ${{cfg.grading.expectations.map(exp => `
                <div class="grading-row">
                  <span class="pass-badge ${{exp.passed ? 'pass' : 'fail'}}">${{exp.passed ? 'PASS' : 'FAIL'}}</span>
                  <div>
                    <div>${{escHtml(exp.text)}}</div>
                    <div class="evidence">${{escHtml(exp.evidence || '')}}</div>
                  </div>
                </div>
              `).join('')}}
            </div>
          </div>`;
      }}

      // Feedback
      const fbKey = `${{ev.dir}}-${{cfg.name}}`;
      const fbValue = feedback[fbKey] || '';

      return `<div class="config-panel ${{i === 0 ? 'active' : ''}}" id="panel-${{ev.dir}}-${{i}}">
        ${{outputHtml}}
        ${{gradingHtml}}
        <div class="feedback-area" style="margin-top:16px;">
          <div style="font-size:12px;font-weight:600;color:#666;margin-bottom:6px;">FEEDBACK</div>
          <textarea placeholder="What worked? What didn't? Leave blank if it looks good."
            onchange="saveFeedback('${{fbKey}}', this.value)"
            oninput="scheduleSave()"
            data-key="${{fbKey}}">${{escHtml(fbValue)}}</textarea>
        </div>
      </div>`;
    }}).join('');

    html += `<div class="card">
      <div class="card-header">Output</div>
      <div class="card-body">
        <div class="config-tabs">${{configTabsHtml}}</div>
        ${{configPanelsHtml}}
      </div>
    </div>`;
  }}

  // Previous iteration outputs
  if (ev.previous_outputs && ev.previous_outputs.length > 0) {{
    const prevHtml = ev.previous_outputs.map(f => `
      <div class="output-file">
        <div class="filename">${{escHtml(f.name)}}</div>
        <pre>${{escHtml(f.content)}}</pre>
      </div>
    `).join('');
    html += `<div class="card">
      <div class="card-header collapsible-header collapsed" onclick="toggleCollapse(this)">Previous Iteration Output</div>
      <div class="card-body collapsible-body hidden">${{prevHtml}}</div>
    </div>`;
  }}

  container.innerHTML = html;
  container.scrollTop = 0;
}}

function switchConfig(el, evalDir, idx) {{
  const parent = el.closest('.card-body');
  parent.querySelectorAll('.config-tab').forEach((t, i) => t.classList.toggle('active', i === idx));
  parent.querySelectorAll('.config-panel').forEach((p, i) => p.classList.toggle('active', i === idx));
}}

function toggleCollapse(header) {{
  header.classList.toggle('collapsed');
  header.nextElementSibling.classList.toggle('hidden');
}}

function saveFeedback(key, value) {{
  feedback[key] = value;
  document.getElementById('savedIndicator').textContent = 'Auto-saved';
  setTimeout(() => {{ document.getElementById('savedIndicator').textContent = ''; }}, 2000);
}}

function scheduleSave() {{
  if (autoSaveTimer) clearTimeout(autoSaveTimer);
  autoSaveTimer = setTimeout(() => {{
    document.querySelectorAll('textarea[data-key]').forEach(ta => {{
      feedback[ta.dataset.key] = ta.value;
    }});
  }}, 500);
}}

function submitAll() {{
  // Capture any unsaved textarea changes
  document.querySelectorAll('textarea[data-key]').forEach(ta => {{
    feedback[ta.dataset.key] = ta.value;
  }});

  const reviews = Object.entries(feedback).map(([run_id, fb]) => ({{
    run_id,
    feedback: fb,
    timestamp: new Date().toISOString(),
  }}));
  const payload = {{ reviews, status: 'complete' }};

  if (FEEDBACK_ENDPOINT === 'download') {{
    // Static mode: trigger download
    const blob = new Blob([JSON.stringify(payload, null, 2)], {{type: 'application/json'}});
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = 'feedback.json';
    a.click();
  }} else {{
    fetch(FEEDBACK_ENDPOINT, {{
      method: 'POST',
      headers: {{'Content-Type': 'application/json'}},
      body: JSON.stringify(payload),
    }}).then(r => r.json()).then(data => {{
      document.getElementById('savedIndicator').textContent = '✓ Feedback saved!';
    }}).catch(err => {{
      console.error(err);
      alert('Failed to save feedback. Try downloading instead.');
    }});
  }}
}}

function renderBenchmark() {{
  const container = document.getElementById('benchmarkContainer');
  if (!BENCHMARK || !BENCHMARK.configs || BENCHMARK.configs.length === 0) {{
    container.innerHTML = '<div class="no-evals">No benchmark data available. Run aggregate_benchmark first.</div>';
    return;
  }}

  let html = `<h2 style="margin-bottom:16px;">Benchmark — ${{BENCHMARK.skill_name || 'skill'}} — Iteration ${{BENCHMARK.iteration || 1}}</h2>`;

  // Aggregate table
  html += `<div class="card"><div class="card-header">Aggregate</div><div class="card-body">
    <table>
      <tr><th>Config</th><th>Pass Rate</th><th>±Stddev</th><th>Avg Duration</th><th>Avg Tokens</th></tr>`;
  BENCHMARK.configs.forEach(cfg => {{
    const agg = cfg.aggregate;
    html += `<tr>
      <td>${{cfg.display_name}}</td>
      <td>${{pct(agg.pass_rate_mean)}}</td>
      <td>±${{pct(agg.pass_rate_stddev)}}</td>
      <td>${{fmtMs(agg.duration_ms_mean)}}</td>
      <td>${{fmtNum(agg.total_tokens_mean)}}</td>
    </tr>`;
  }});
  html += '</table>';

  if (BENCHMARK.delta && Object.keys(BENCHMARK.delta).length > 0) {{
    const d = BENCHMARK.delta;
    html += `<div style="margin-top:12px;font-size:13px;">
      <strong>Delta (with skill vs baseline):</strong>
      Pass rate: <span class="${{d.pass_rate >= 0 ? 'delta-positive' : 'delta-negative'}}">${{d.pass_rate >= 0 ? '+' : ''}}${{pct(d.pass_rate)}}</span> |
      Duration: <span class="${{d.duration_ms <= 0 ? 'delta-positive' : 'delta-negative'}}">${{d.duration_ms >= 0 ? '+' : ''}}${{fmtMs(d.duration_ms)}}</span> |
      Tokens: <span class="${{d.total_tokens <= 0 ? 'delta-positive' : 'delta-negative'}}">${{d.total_tokens >= 0 ? '+' : ''}}${{fmtNum(d.total_tokens)}}</span>
    </div>`;
  }}
  html += '</div></div>';

  // Per-eval tables
  BENCHMARK.configs.forEach(cfg => {{
    html += `<div class="card"><div class="card-header">${{cfg.display_name}} — Per Eval</div><div class="card-body">
      <table>
        <tr><th>Eval</th><th>Pass Rate</th><th>Passed/Total</th><th>Duration</th><th>Tokens</th></tr>`;
    cfg.evals.forEach(e => {{
      html += `<tr>
        <td>${{escHtml(e.eval_name)}}</td>
        <td>${{pct(e.pass_rate)}}</td>
        <td>${{e.assertions_passed}}/${{e.assertions_total}}</td>
        <td>${{fmtMs(e.duration_ms)}}</td>
        <td>${{fmtNum(e.total_tokens)}}</td>
      </tr>`;
    }});
    html += '</table></div></div>';
  }});

  if (BENCHMARK.analyst_notes) {{
    html += `<div class="analyst-notes"><strong>Analyst Notes</strong><br><pre>${{escHtml(BENCHMARK.analyst_notes)}}</pre></div>`;
  }}

  container.innerHTML = html;
}}

function escHtml(str) {{
  if (!str) return '';
  return String(str)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}}

function pct(v) {{ return v != null ? (v * 100).toFixed(1) + '%' : '—'; }}
function fmtMs(v) {{ return v ? v.toLocaleString() + 'ms' : '—'; }}
function fmtNum(v) {{ return v ? v.toLocaleString() : '—'; }}

// Init
if (EVALS.length > 0) {{
  renderEval(0);
}} else {{
  document.getElementById('evalContainer').innerHTML = '<div class="no-evals">No eval results found in this iteration directory.</div>';
  document.getElementById('evalNav').style.display = 'none';
}}
</script>
</body>
</html>"""


def run_server(html: str, iteration_dir: Path, port: int = 8765) -> None:
    """Serve the review HTML and handle feedback POST."""
    import io

    class Handler(http.server.BaseHTTPRequestHandler):
        def log_message(self, format, *args):
            pass  # Suppress request logs

        def do_GET(self):
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.end_headers()
            self.wfile.write(html.encode("utf-8"))

        def do_POST(self):
            if self.path == "/submit_feedback":
                length = int(self.headers.get("Content-Length", 0))
                body = self.rfile.read(length)
                try:
                    data = json.loads(body)
                    feedback_path = iteration_dir / "feedback.json"
                    with open(feedback_path, "w") as f:
                        json.dump(data, f, indent=2)
                    self.send_response(200)
                    self.send_header("Content-Type", "application/json")
                    self.end_headers()
                    self.wfile.write(json.dumps({"status": "saved", "path": str(feedback_path)}).encode())
                    print(f"Feedback saved to: {feedback_path}")
                except Exception as e:
                    self.send_response(500)
                    self.end_headers()
                    self.wfile.write(str(e).encode())
            else:
                self.send_response(404)
                self.end_headers()

    server = http.server.HTTPServer(("localhost", port), Handler)
    url = f"http://localhost:{port}"
    print(f"Review server running at {url}")
    print("Press Ctrl+C to stop.")

    try:
        webbrowser.open(url)
    except Exception:
        print(f"Could not open browser automatically. Navigate to {url}")

    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nServer stopped.")


def main():
    parser = argparse.ArgumentParser(description="Generate skill eval review viewer")
    parser.add_argument("iteration_dir", help="Path to iteration-N directory")
    parser.add_argument("--skill-name", default="skill", help="Skill display name")
    parser.add_argument("--benchmark", help="Path to benchmark.json")
    parser.add_argument("--previous-workspace", help="Path to previous iteration directory")
    parser.add_argument("--static", help="Write standalone HTML to this path instead of starting server")
    parser.add_argument("--port", type=int, default=8765, help="Server port (default: 8765)")
    args = parser.parse_args()

    iteration_dir = Path(args.iteration_dir).resolve()
    if not iteration_dir.is_dir():
        print(f"Error: {iteration_dir} is not a directory", file=sys.stderr)
        sys.exit(1)

    previous_dir = Path(args.previous_workspace).resolve() if args.previous_workspace else None

    print(f"Loading eval data from {iteration_dir}...")
    evals = collect_eval_data(iteration_dir, previous_dir)
    print(f"Found {len(evals)} eval(s)")

    benchmark = None
    if args.benchmark:
        benchmark = load_json(Path(args.benchmark))
    elif (iteration_dir / "benchmark.json").exists():
        benchmark = load_json(iteration_dir / "benchmark.json")

    html = generate_html(
        args.skill_name,
        evals,
        benchmark,
        iteration_dir,
        static=bool(args.static),
    )

    if args.static:
        output_path = Path(args.static)
        with open(output_path, "w", encoding="utf-8") as f:
            f.write(html)
        print(f"Static review written to: {output_path}")
    else:
        run_server(html, iteration_dir, args.port)


if __name__ == "__main__":
    main()
