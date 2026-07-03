# murakumo-studio

A local inference desktop app (like [LM Studio](https://lmstudio.ai/)) built on
[`kotoba-lang/inference`](https://github.com/kotoba-lang/inference) — manage
local GGUF models, chat with them, expose an OpenAI-compatible local server for
other apps, and optionally announce your node to the
[murakumo](https://github.com/kotoba-lang/murakumo) fleet economy.

Design/architecture: `90-docs/adr/2607032700-murakumo-studio-local-inference-desktop-app.md`
in the [com-junkawasaki](https://github.com/com-junkawasaki/com-junkawasaki)
superproject (this repo is registered there via
[west](https://docs.zephyrproject.org/latest/develop/west/)).

## Status (v1, Phase 1 of the ADR's roadmap)

- **Inference backend is CPU (`num.cpu`), not GPU/WebGPU.** `kotoba-lang/inference`
  is pre-1.0; the ADR explicitly scopes WebGPU to Phase 2. `/v1/chat/completions`
  calls `kotodama.inference.core/generate` dynamically (`requiring-resolve`) —
  if that function isn't in your pinned `inference` build yet, or its signature
  has moved, chat requests return a clear `503`/`500` rather than crashing the
  sidecar; every other feature (model manager, download, fleet announce) works
  independently of that.
- **Real end-to-end generation now exists upstream, but isn't wired in yet.**
  [`kotoba-lang/inference#4`](https://github.com/kotoba-lang/inference/pull/4)
  (open, not merged) adds a genuinely working tokenizer + autoregressive decode
  loop that runs the real full 42-layer `gemma4:e4b` on CPU without
  crashing/NaN'ing. Two things block wiring it into `engine.clj` as-is:
  1. The stable, working `generate` lives at `kotodama.inference.decode/generate`
     (portable, takes an injected `:kotodama/forward-fn` + built tokenizer — a
     primitive, not a one-call convenience fn) plus a JVM host adapter that's
     currently only in `verify/` (smoke-test code, not a published API).
     `kotodama.inference.core/generate` — what this engine actually calls — is
     still the old unimplemented stub; the PR doesn't touch `core.cljc`.
  2. **Output quality isn't there yet**: the PR's own report shows real
     vocabulary tokens across mixed scripts, not coherent text, attributed to
     missing AltUp / per-layer-embedding layers Gemma4 needs. Speed is also
     ~100s/token on CPU (no KV-cache yet) — not viable for interactive chat
     even once wired.

  Follow-up once PR #4 is reviewed/merged: either give `inference` a stable
  `core.cljc` entry point that wraps `decode/generate` + a JVM forward-fn (the
  right long-term fix), or have this engine build its own forward-fn/tokenizer
  the way the verify/ adapter does — but only after the AltUp gap is closed,
  since wiring it now would just serve incoherent text to users.
- **Model management, local OpenAI-compatible server, and fleet announce are
  real and tested** (see `test/murakumo_studio/models_test.clj`, and manual
  end-to-end verification of `src/murakumo_studio/engine.clj` via `curl`).
- **Fleet participation is announce-only.** Joining as a `murakumo.infer`
  compute worker (did:key/CACAO identity, actual shard-plan participation) is
  Phase 3, not implemented here.
- **Packaging is dev-only.** `tauri dev` shells out to a `clojure` binary on
  `PATH` and assumes the JVM sidecar's source tree sits next to the app
  (`MURAKUMO_STUDIO_REPO` env var to override). Bundling a JVM into a
  distributable `.app`/`.dmg` is not attempted yet.

## Architecture

```
Tauri 2 (Rust shell, tauri/)
 └─ webview: ClojureScript (shadow-cljs + reagent), kotoba-ui.core + appkit.core
      talks HTTP/JSON to ↓
JVM engine sidecar (src/murakumo_studio/engine.clj, spawned by the Rust shell)
 ├─ murakumo_studio.models  — local GGUF scan (~/.murakumo-studio/models,
 │                             ~/.ollama/models) + HF Hub download
 ├─ murakumo_studio.fleet   — announce-only client for
 │                             gftdcojp/cloud-murakumo-fleet's /infer/* API
 └─ kotodama.inference.core/generate (kotoba-lang/inference, resolved
                             dynamically — see Status above)
```

## Prerequisites

- [Clojure CLI](https://clojure.org/guides/install_clojure) (`clojure`/`clj` on `PATH`)
- [babashka](https://babashka.org/) (`bb`) for CSS generation / test tasks
- Node.js + npm, for shadow-cljs and the Tauri CLI
- Rust toolchain (for `tauri dev` / `tauri build`)

If checked out inside the `com-junkawasaki` west superproject (sibling to
`orgs/kotoba-lang/inference`, `orgs/kotoba-lang/appkit`, etc.), use the `:dev`
alias to pick up `:local/root` sibling deps instead of the published git pins:
`clojure -M:dev:engine`, and set `MURAKUMO_STUDIO_ENGINE_ALIAS=:dev:engine`
before `tauri dev` (see `tauri/src-tauri/src/main.rs`).

## Dev loop

```bash
npm install
bb ui-css                 # generate tauri/dist/vendor/kotoba-ui.css
npm run gui:watch &        # shadow-cljs watch → tauri/dist/js/main.js
cd tauri && npm install && npm run tauri dev
```

The Rust shell spawns the JVM engine sidecar on startup
(`clojure -M:engine`, `http://127.0.0.1:8721` by default — override with
`MURAKUMO_STUDIO_PORT`). You can also run the sidecar standalone for
frontend-only iteration:

```bash
clojure -M:engine    # or -M:dev:engine inside the west superproject
curl localhost:8721/health
```

## Tests

```bash
clojure -M:test     # murakumo_studio.models (local + Ollama manifest scan)
```

## License

TBD.
