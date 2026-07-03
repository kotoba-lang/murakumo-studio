// murakumo-studio — Tauri v2 shell. Frontend is ClojureScript (shadow-cljs +
// reagent, tauri/dist/js/main.js). This process's only job is to spawn and
// supervise the local inference engine sidecar (../../src/murakumo_studio/
// engine.clj, a JVM process reached over HTTP by the frontend — see
// murakumo-studio.client). No business logic lives in Rust; unlike
// gftdcojp/manimani/tauri this app has no in-process kotoba-datomic store.
//
// v1 packaging note (ADR-2607032700): this only works for `tauri dev` today
// — it shells out to `clojure`, which must be on PATH, and assumes the repo
// checkout sits next to this binary's working directory. Bundling a JVM
// alongside the native binary for a distributable .app is a documented
// follow-up, not attempted here.
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use std::path::PathBuf;
use std::process::{Child, Command};
use std::sync::Mutex;
use tauri::Manager;

struct EngineProcess(Mutex<Option<Child>>);

fn repo_root() -> PathBuf {
    if let Ok(p) = std::env::var("MURAKUMO_STUDIO_REPO") {
        return PathBuf::from(p);
    }
    // `cargo run` / `tauri dev` sets cwd to the Cargo package dir
    // (tauri/src-tauri/), which is 2 levels under the repo root where
    // deps.edn lives — walk up looking for it rather than assuming a fixed
    // depth (Tauri CLI versions have moved this before).
    if let Ok(cwd) = std::env::current_dir() {
        let mut dir = cwd.as_path();
        loop {
            if dir.join("deps.edn").is_file() {
                return dir.to_path_buf();
            }
            match dir.parent() {
                Some(p) => dir = p,
                None => break,
            }
        }
    }
    PathBuf::from(std::env::var("HOME").unwrap_or_default()).join("murakumo-studio")
}

fn engine_alias() -> String {
    // monorepo dev (checked out under a west superproject next to
    // orgs/kotoba-lang/inference) needs `:dev:engine` to pick up the sibling
    // :local/root override; a standalone clone uses the published git/sha
    // pin via plain `:engine`. See deps.edn.
    std::env::var("MURAKUMO_STUDIO_ENGINE_ALIAS").unwrap_or_else(|_| ":engine".to_string())
}

fn spawn_engine() -> std::io::Result<Child> {
    Command::new("clojure")
        .arg(format!("-M{}", engine_alias()))
        .current_dir(repo_root())
        .spawn()
}

fn main() {
    tauri::Builder::default()
        .manage(EngineProcess(Mutex::new(None)))
        .setup(|app| {
            match spawn_engine() {
                Ok(child) => {
                    let state = app.state::<EngineProcess>();
                    *state.0.lock().unwrap() = Some(child);
                }
                Err(e) => {
                    eprintln!(
                        "murakumo-studio: failed to spawn engine sidecar (`clojure` on PATH? repo at {:?}?): {e}",
                        repo_root()
                    );
                }
            }
            Ok(())
        })
        .on_window_event(|window, event| {
            if let tauri::WindowEvent::Destroyed = event {
                let state = window.state::<EngineProcess>();
                let child = state.0.lock().unwrap().take();
                if let Some(mut child) = child {
                    let _ = child.kill();
                }
            }
        })
        .run(tauri::generate_context!())
        .expect("error while running murakumo-studio");
}
