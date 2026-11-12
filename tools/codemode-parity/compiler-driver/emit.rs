#![feature(rustc_private)]
extern crate rustc_driver;
extern crate rustc_hir;
extern crate rustc_interface;
extern crate rustc_middle;
extern crate rustc_span;

use rustc_hir::def::{DefKind, Namespace, Res};
use rustc_hir::def_id::DefId;
use rustc_hir::intravisit::{self, Visitor};
use rustc_middle::hir::nested_filter;
use rustc_middle::thir::ExprKind;
use rustc_middle::ty::{self, TyCtxt, TypeVisitableExt};
use rustc_span::{FileName, Span, Pos};
use std::collections::{BTreeMap, HashMap, HashSet};
use std::fs::File;
use std::io::{BufWriter, Write};
use std::path::PathBuf;

fn quote(value: &str) -> String {
    let mut output = String::from("\"");
    for ch in value.chars() {
        match ch {
            '"' => output.push_str("\\\""), '\\' => output.push_str("\\\\"), '\n' => output.push_str("\\n"), '\r' => output.push_str("\\r"), '\t' => output.push_str("\\t"),
            ch if ch < '\u{20}' => output.push_str(&format!("\\u{:04x}", ch as u32)), ch => output.push(ch),
        }
    }
    output.push('"'); output
}

struct Facts<'tcx> {
    tcx: TyCtxt<'tcx>, project: PathBuf, output: BufWriter<File>, emitted: HashSet<DefId>, bytes: usize, references: usize,
    selection: Option<Selection>, filtered: BTreeMap<(String, String), usize>, observed_references: usize,
}
struct Selection { raw: String, files: HashMap<PathBuf, String> }
impl Selection {
    fn read(path: &str, project: &PathBuf) -> Self {
        let raw = std::fs::read_to_string(path).expect("read pinned source selector");
        assert!(raw.len() <= 1024 * 1024, "source selector exceeds 1 MiB");
        let mut files = HashMap::new();
        for line in raw.lines() {
            let (path, hash) = line.split_once('\t').expect("selector requires exact path and compiler source hash");
            let path = PathBuf::from(path).canonicalize().expect("selected source file exists");
            assert!(path.starts_with(project) && files.insert(path, hash.to_string()).is_none(), "source selector escapes root or repeats a file");
        }
        assert!(!files.is_empty() && files.len() <= 10000, "source selector has an invalid file count");
        Self { raw, files }
    }
}
impl<'tcx> Facts<'tcx> {
    fn row(&mut self, row: String) {
        self.bytes += row.len() + 1;
        assert!(self.bytes <= 256 * 1024 * 1024, "compiler fact output exceeds 256 MiB; incomplete proof");
        writeln!(self.output, "{row}").expect("write complete compiler fact row");
    }
    fn identity(&self, id: DefId) -> String {
        format!("{{\"crate\":{},\"crateNumber\":{},\"stableCrateId\":{},\"crateHash\":{},\"index\":{},\"path\":{},\"kind\":{}}}",
            quote(self.tcx.crate_name(id.krate).as_str()), id.krate.as_u32(), quote(&format!("{:?}", self.tcx.stable_crate_id(id.krate))),
            quote(&self.tcx.crate_hash(id.krate).to_string()), id.index.as_u32(), quote(&self.tcx.def_path_str(id)), quote(&format!("{:?}", self.tcx.def_kind(id))))
    }
    fn span(&self, span: Span) -> String {
        if span.is_dummy() { return "{\"state\":\"dummy\"}".into(); }
        let map = self.tcx.sess.source_map();
        let lo = map.lookup_byte_offset(span.lo()); let hi = map.lookup_byte_offset(span.hi());
        if lo.sf.start_pos != hi.sf.start_pos { return "{\"state\":\"different-files\"}".into(); }
        let FileName::Real(ref name) = lo.sf.name else { return "{\"state\":\"generated-file\"}".into(); };
        let Some(local) = name.local_path() else { return "{\"state\":\"remapped-file\"}".into(); };
        let path = if local.is_absolute() { local.to_path_buf() } else { std::env::current_dir().unwrap().join(local) };
        let path = path.canonicalize().unwrap_or(path);
        if !path.starts_with(&self.project) { return "{\"state\":\"outside-project\"}".into(); }
        let available = map.span_to_snippet(span).is_ok();
        format!("{{\"state\":{},\"file\":{},\"start\":{},\"end\":{},\"rootContext\":{},\"compilerSourceHash\":{}}}",
            quote(if available { "source" } else { "source-unavailable" }), quote(&path.to_string_lossy()),
            lo.sf.original_relative_byte_pos(span.lo()).to_u32(), lo.sf.original_relative_byte_pos(span.hi()).to_u32(), span.ctxt().is_root(), quote(&format!("{:?}", lo.sf.src_hash)))
    }
    fn declaration(&mut self, id: DefId) {
        if !self.emitted.insert(id) { return; }
        let span = self.span(self.tcx.def_span(id));
        self.row(format!("{{\"tag\":\"declaration\",\"definition\":{},\"span\":{span}}}", self.identity(id)));
    }
    fn reference(&mut self, owner: DefId, span: Span, kind: &str, namespace: &str, target: Option<DefId>, resolution: &str) {
        self.declaration(owner);
        if let Some(id) = target { self.declaration(id); }
        self.observed_references += 1;
        if let Some(reason) = self.omission(span) {
            let category = match resolution {
                "Def" | "FnDef" | "dynamic-call" | "generic-call" | "trait-contract-call" | "indirect-call" => resolution,
                "Err" => "unresolved", _ => "non-definition-resolution",
            };
            *self.filtered.entry((reason.to_string(), category.to_string())).or_default() += 1;
            return;
        }
        self.references += 1;
        self.row(format!("{{\"tag\":\"reference\",\"owner\":{},\"span\":{},\"kind\":{},\"namespace\":{},\"target\":{},\"resolution\":{}}}",
            self.identity(owner), self.span(span), quote(kind), quote(namespace), target.map(|id| self.identity(id)).unwrap_or_else(|| "null".into()), quote(resolution)));
    }
    fn omission(&self, span: Span) -> Option<&'static str> {
        let Some(selection) = &self.selection else { return None; };
        if span.is_dummy() { return Some("dummy-source-span"); }
        if !span.ctxt().is_root() { return Some("non-root-source-context"); }
        let map = self.tcx.sess.source_map();
        let lo = map.lookup_byte_offset(span.lo()); let hi = map.lookup_byte_offset(span.hi());
        if lo.sf.start_pos != hi.sf.start_pos { return Some("different-source-files"); }
        let FileName::Real(ref name) = lo.sf.name else { return Some("generated-source-file"); };
        let Some(path) = name.local_path() else { return Some("remapped-source-file"); };
        let path = if path.is_absolute() { path.to_path_buf() } else { std::env::current_dir().unwrap().join(path) };
        let path = path.canonicalize().unwrap_or(path);
        let Some(expected) = selection.files.get(&path) else { return Some("outside-selected-source-files"); };
        assert_eq!(&format!("{:?}", lo.sf.src_hash), expected, "compiler source differs from selected immutable bytes");
        if map.span_to_snippet(span).is_err() { return Some("selected-source-unavailable"); }
        None
    }
    fn resolved(&mut self, owner: DefId, span: Span, kind: &str, namespace: Option<&str>, resolution: Res) {
        let (target, ns, label) = match resolution {
            Res::Def(kind, id) => (Some(id), namespace.unwrap_or(namespace_for(kind)), "Def".to_string()),
            other => (None, namespace.unwrap_or("unknown"), format!("{other:?}")),
        };
        self.reference(owner, span, kind, ns, target, &label);
    }
    fn callable(&self, ty: ty::Ty<'tcx>) -> (Option<DefId>, &'static str) {
        if let ty::FnDef(id, args) = *ty.kind() {
            let dynamic = args.skip_binder().types().any(|ty| ty.walk().any(|arg| arg.as_type().is_some_and(|ty| matches!(ty.kind(), ty::Dynamic(..)))));
            let trait_contract = self.tcx.opt_associated_item(id).is_some_and(|_| matches!(self.tcx.def_kind(self.tcx.parent(id)), DefKind::Trait));
            let resolution = if dynamic { "dynamic-call" } else if args.has_param() { "generic-call" } else if trait_contract { "trait-contract-call" } else { "FnDef" };
            (Some(id), resolution)
        } else { (None, "indirect-call") }
    }
}
fn namespace_for(kind: DefKind) -> &'static str {
    match kind.ns() {
        Some(Namespace::TypeNS) => "type", Some(Namespace::ValueNS) => "value", Some(Namespace::MacroNS) => "macro", None => "unknown",
    }
}
impl<'tcx> Visitor<'tcx> for Facts<'tcx> {
    type NestedFilter = nested_filter::All;
    fn maybe_tcx(&mut self) -> TyCtxt<'tcx> { self.tcx }
    fn visit_path(&mut self, path: &rustc_hir::Path<'tcx>, id: rustc_hir::HirId) {
        self.resolved(id.owner.def_id.to_def_id(), path.span, "path", None, path.res);
        intravisit::walk_path(self, path);
    }
    fn visit_use(&mut self, path: &'tcx rustc_hir::UsePath<'tcx>, id: rustc_hir::HirId) {
        for (namespace, resolution) in [("type", path.res.type_ns), ("value", path.res.value_ns), ("macro", path.res.macro_ns)] {
            if let Some(resolution) = resolution { self.resolved(id.owner.def_id.to_def_id(), path.span, "import", Some(namespace), resolution); }
        }
        intravisit::walk_use(self, path, id);
    }
}

struct Capture;
impl rustc_driver::Callbacks for Capture {
    fn after_analysis<'tcx>(&mut self, _: &rustc_interface::interface::Compiler, tcx: TyCtxt<'tcx>) -> rustc_driver::Compilation {
        let project = PathBuf::from(std::env::var("SPLICE_COMPILER_PROJECT_ROOT").expect("explicit project root")).canonicalize().unwrap();
        let selection = std::env::var("SPLICE_COMPILER_SOURCE_SELECTION").ok().map(|path| Selection::read(&path, &project));
        let output = File::create(std::env::var("SPLICE_COMPILER_FACTS").expect("explicit artifact output")).unwrap();
        let mut facts = Facts { tcx, project, output: BufWriter::new(output), emitted: HashSet::new(), bytes: 0, references: 0, selection, filtered: BTreeMap::new(), observed_references: 0 };
        let selected = facts.selection.as_ref().map(|value| format!("{{\"content\":{},\"files\":{},\"rootContextOnly\":true}}", quote(&value.raw), value.files.len())).unwrap_or_else(|| "null".into());
        facts.row(format!("{{\"tag\":\"session\",\"schema\":1,\"root\":{},\"selection\":{selected},\"behavioralCoverage\":false}}", facts.identity(rustc_hir::def_id::CRATE_DEF_ID.to_def_id())));
        for id in tcx.iter_local_def_id() { facts.declaration(id.to_def_id()); }
        tcx.hir_walk_toplevel_module(&mut facts);
        for owner in tcx.hir_body_owners() {
            let Ok((body, _)) = tcx.thir_body(owner) else {
                facts.row(format!("{{\"tag\":\"debt\",\"owner\":{},\"reason\":\"thir-unavailable\"}}", facts.identity(owner.to_def_id()))); continue;
            };
            let body = body.borrow();
            for expr in &body.exprs {
                if matches!(expr.ty.kind(), ty::FnDef(..)) {
                    let (target, resolution) = facts.callable(expr.ty);
                    facts.reference(owner.to_def_id(), expr.span, "typed-value", "value", target, resolution);
                }
                if let ExprKind::Call { ty, fn_span, .. } = expr.kind {
                    let (target, resolution) = facts.callable(ty);
                    facts.reference(owner.to_def_id(), expr.span, "call", "value", target, resolution);
                    facts.reference(owner.to_def_id(), fn_span, "callee", "value", target, resolution);
                }
            }
        }
        let filtered = facts.filtered.iter().map(|((reason, resolution), count)| format!("{{\"reason\":{},\"resolution\":{},\"count\":{count}}}", quote(reason), quote(resolution))).collect::<Vec<_>>().join(",");
        facts.row(format!("{{\"tag\":\"complete\",\"declarations\":{},\"references\":{},\"observedReferences\":{},\"filteredReferences\":[{filtered}],\"behavioralCoverage\":false}}", facts.emitted.len(), facts.references, facts.observed_references));
        facts.output.flush().expect("flush complete fact proof");
        rustc_driver::Compilation::Stop
    }
}
fn main() {
    rustc_driver::run_compiler(&std::env::args().collect::<Vec<_>>(), &mut Capture);
}
