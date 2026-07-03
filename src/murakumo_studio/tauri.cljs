(ns murakumo-studio.tauri
  "Tauri IPC invoke helper. murakumo-studio's actual data path is HTTP to the
  local engine sidecar (see murakumo-studio.client) — this ns is only for the
  handful of things that must go through the Rust shell (native file/save
  dialogs, sidecar lifecycle queries).")

(def tauri?
  (boolean (and (exists? js/window) (.-__TAURI__ js/window))))

(defn invoke
  "Call a Tauri command; returns a JS Promise. Rejects immediately when not
  running under Tauri (e.g. `shadow-cljs watch app` opened in a plain browser
  tab during frontend-only development)."
  [cmd args]
  (if tauri?
    (.invoke (.-core (.-__TAURI__ js/window)) cmd (clj->js (or args {})))
    (js/Promise.reject (js/Error. (str "not running under Tauri: " cmd)))))
