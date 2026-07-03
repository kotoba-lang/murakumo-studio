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

- **Chat generation is wired to real `kotoba-lang/inference` and verified
  working end-to-end — but output quality isn't there yet.** `/v1/chat/completions`
  calls `kotodama.inference.host.jvm/generate`
  ([inference#5](https://github.com/kotoba-lang/inference/pull/5), merged),
  a stable JVM entry point that loads the real GGUF, runs the full 42-layer
  CPU forward pass with a verified-correct KV-cache (identical output
  with/without — pure optimization, ~2.76× speedup measured upstream), and
  returns real generated text. Verified live: prompt "The capital of France
  is" → `"يبهيبهيبهيبه"` — a real (not mocked, not an error) but **not
  coherent** response; a deep-layer numerical drift versus reference
  implementations (llama.cpp/HF/Ollama) is still open upstream. Every
  response carries a `murakumo_studio/quality_note` field (and the Chat tab
  shows a ⚠ banner) saying so explicitly — this isn't hidden from users.
  If `kotodama.inference.host.jvm/generate` isn't resolvable at all (e.g. an
  older pinned `inference` build), chat requests get a clear `503`/`500`
  instead of crashing the sidecar; every other feature (model manager,
  download, fleet announce) works independently of that.
- **CPU generation is slow: tens of seconds per token**, dominated by
  re-reading and dequantizing GGUF weights from disk per session (no weight
  caching or session reuse across chat turns yet — each `/v1/chat/completions`
  call currently opens and closes its own session). `Settings → max tokens`
  defaults to 32, not the more conventional 256, to keep replies from taking
  many minutes. GPU/WebGPU (ADR-2607032700 Phase 2) is the real fix.
- **Model management (local scan, HF Hub search/browse/resumable download),
  streaming chat (SSE, `/v1/chat/completions` with `stream: true` — real
  token-by-token via `:kotodama/on-token`, not simulated), local
  OpenAI-compatible server, and fleet announce are real and tested**
  (see `test/murakumo_studio/models_test.clj`, live browser session testing
  of `src/murakumo_studio/ui.cljs` against the real running engine, and manual
  end-to-end verification of `src/murakumo_studio/engine.clj` via `curl`,
  including a real ~6.5min/4-token generation run against the actual
  `gemma4:e4b` GGUF).
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
 └─ kotodama.inference.host.jvm/generate (kotoba-lang/inference, resolved
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
