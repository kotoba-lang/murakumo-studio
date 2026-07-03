(ns murakumo-studio.models
  "Local model discovery + Hugging Face Hub download. Scans
  ~/.murakumo-studio/models/*.gguf (models murakumo-studio itself downloaded)
  and, read-only, an existing Ollama install's blob store so users don't have
  to re-download models they already have (ADR-2607032700 §4)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [cheshire.core :as json])
  (:import [java.io File]
           [java.net URI]))

(defn- home [] (System/getProperty "user.home"))

(defn studio-home
  "Base dir for murakumo-studio's own state (models/config). Public so tests
  can `with-redefs` it to a temp dir instead of touching the real $HOME."
  ^File []
  (io/file (or (System/getenv "MURAKUMO_STUDIO_HOME") (str (home) "/.murakumo-studio"))))

(defn studio-models-dir ^File []
  (doto (io/file (studio-home) "models") (.mkdirs)))

(defn ollama-root
  "Public (like studio-home) so tests can point it at a synthetic manifest
  tree instead of the real Ollama install."
  ^File []
  (io/file (or (System/getenv "OLLAMA_MODELS") (str (home) "/.ollama/models"))))

(defn- scan-local-gguf []
  (for [^File f (or (.listFiles (studio-models-dir)) [])
        :when (and (.isFile f) (str/ends-with? (.getName f) ".gguf"))]
    {:id (.getName f)
     :path (.getAbsolutePath f)
     :size_bytes (.length f)
     :backend "cpu"
     :context_length nil
     :source "local"}))

(defn- ollama-manifest-models
  "Ollama stores `models/manifests/<registry>/<namespace>/<name>/<tag>` JSON
  files pointing at content-addressed blobs in `models/blobs/sha256-<hex>`.
  We only care about the layer whose mediaType marks it as the GGUF weights."
  []
  (let [root (ollama-root)
        manifests-root (io/file root "manifests")]
    (when (.isDirectory manifests-root)
      (let [prefix-len (inc (count (.getAbsolutePath manifests-root)))]
        (keep
         (fn [^File f]
           (when (.isFile f)
             (let [rel (subs (.getAbsolutePath f) prefix-len)
                   parts (str/split rel (re-pattern (str "\\" File/separator)))]
               (when (>= (count parts) 3)
                 (let [tag (peek parts)
                       ;; parts = [registry-host namespace... name tag]; drop
                       ;; the host, keep namespace/name as the model id.
                       name-parts (butlast (rest parts))
                       model-name (str/join "/" name-parts)]
                   (when-let [manifest (try (json/parse-string (slurp f) true)
                                             (catch Exception _ nil))]
                     (when-let [gguf-layer (first (filter #(= (:mediaType %)
                                                               "application/vnd.ollama.image.model")
                                                           (:layers manifest)))]
                       (let [blob (io/file root "blobs" (str/replace (:digest gguf-layer) ":" "-"))]
                         (when (.isFile blob)
                           {:id (str model-name ":" tag)
                            :path (.getAbsolutePath blob)
                            :size_bytes (or (:size gguf-layer) (.length blob))
                            :backend "cpu"
                            :context_length nil
                            :source "ollama"})))))))))
         (file-seq manifests-root))))))

(defn scan-all []
  (vec (concat (scan-local-gguf) (ollama-manifest-models))))

(defonce cache (atom []))

(defn refresh! []
  (reset! cache (scan-all))
  @cache)

(defn list-models [] @cache)

(defn path-for [id]
  (:path (first (filter #(= (:id %) id) @cache))))

(defn download!
  "Full (non-resumable) download of a single GGUF file from a public
  Hugging Face Hub repo. Resumable HTTP Range download is a documented
  follow-up (ADR-2607032700 §4) — not implemented in v1."
  [{:keys [repo-id file]}]
  (when (or (str/blank? repo-id) (str/blank? file))
    (throw (ex-info "repo-id and file are required" {:repo-id repo-id :file file})))
  (let [url (str "https://huggingface.co/" repo-id "/resolve/main/" file "?download=true")
        dest (io/file (studio-models-dir) (last (str/split file #"/")))]
    (with-open [in (.openStream (.toURL (URI. url)))]
      (io/copy in dest))
    (refresh!)
    {:path (.getAbsolutePath dest) :size_bytes (.length dest) :repo_id repo-id :file file}))
