(ns murakumo-studio.client
  "HTTP client for the local murakumo-studio-engine sidecar
  (src/murakumo_studio/engine.clj), spawned and supervised by the Tauri Rust
  shell (tauri/src-tauri/src/main.rs). The frontend never talks to
  kotoba-lang/inference directly — it only ever speaks HTTP/JSON to this
  localhost process, which is the only thing running on the JVM.")

(def default-port 8721)

(defn base-url []
  (str "http://127.0.0.1:" default-port))

(defn- json-fetch [path opts]
  (-> (js/fetch (str (base-url) path)
                (clj->js (merge {:headers {"Content-Type" "application/json"}} opts)))
      (.then (fn [res]
               (if (.-ok res)
                 (.json res)
                 (.then (.text res)
                        (fn [body]
                          (throw (js/Error. (str "HTTP " (.-status res) ": " body))))))))
      (.then #(js->clj % :keywordize-keys true))))

(defn health []
  (json-fetch "/health" {:method "GET"}))

(defn list-models []
  (json-fetch "/v1/models" {:method "GET"}))

(defn scan-local-models []
  (json-fetch "/models/scan" {:method "POST"}))

(defn download-model [{:keys [repo-id file]}]
  (json-fetch "/models/download"
              {:method "POST"
               :body (js/JSON.stringify (clj->js {:repo_id repo-id :file file}))}))

(defn chat-completion
  "Non-streaming v1 (kotoba-lang/inference's decode loop is single-request; a
  streaming /v1/chat/completions is a follow-up once token-by-token flush is
  wired through the engine)."
  [{:keys [model messages temperature max-tokens]}]
  (json-fetch "/v1/chat/completions"
              {:method "POST"
               :body (js/JSON.stringify
                      (clj->js {:model model
                                :messages messages
                                :temperature (or temperature 0.7)
                                :max_tokens (or max-tokens 256)
                                :stream false}))}))

(defn fleet-status []
  (json-fetch "/fleet/status" {:method "GET"}))

(defn fleet-join []
  (json-fetch "/fleet/join" {:method "POST"}))

(defn fleet-leave []
  (json-fetch "/fleet/leave" {:method "POST"}))
