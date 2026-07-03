(ns murakumo-studio.ui
  "murakumo-studio's view tree. Structural chrome (panel/list-view/tab-bar/
  badge/divider) comes from appkit.core (desktop binding) / kotoba-ui.core
  (kotoba-lang default design system, ADR-2607022800). Interactive controls
  that need a live reagent handler (button/text-field/text-area/menu-select/
  toggle) use kotoba-ui's `:on-*` escape hatches directly — only `button`
  lacks one (shitsuke's :act SSR contract has no on-click), so `btn` below is
  a small hand-rolled button styled via kotoba-ui's exposed `class-name`,
  mirroring gftdcojp/manimani/src/manimani/ui.cljc's `sbutton`."
  (:require [appkit.core :as shape]
            [kotoba-ui.core :as ui]
            [murakumo-studio.state :as state]
            [murakumo-studio.client :as client]))

;; ---------------------------------------------------------------------------
;; small local helpers

(defn- btn
  ([label on-click] (btn label on-click {}))
  ([label on-click {:keys [disabled? class]}]
   [:button {:class (str (ui/class-name :button) (when class (str " " class)))
             :type "button"
             :disabled (boolean disabled?)
             :on-click on-click}
    label]))

(defn- fmt-bytes [n]
  (cond
    (nil? n) "?"
    (>= n (* 1024 1024 1024)) (str (.toFixed (/ n 1024 1024 1024) 1) " GiB")
    (>= n (* 1024 1024)) (str (.toFixed (/ n 1024 1024) 1) " MiB")
    :else (str n " B")))

(defn- on-tab-bar-click
  "kotoba-ui tab-bar's act contract (data-act=tab id) → tab switch. Same
  delegation pattern as manimani.ui/on-tab-click."
  [on-select]
  (fn [e]
    (when-let [el (some-> (.-target e) (.closest "[data-act]"))]
      (when-let [act (.getAttribute el "data-act")]
        (on-select (keyword act))))))

;; ---------------------------------------------------------------------------
;; header + tabs

(def tabs [[:models "Models"] [:chat "Chat"] [:download "Download"]
           [:settings "Settings"] [:fleet "Fleet"]])

(defn- engine-badge []
  (let [{:keys [status error]} (:engine @state/state)]
    [ui/badge (case status
                :ready "engine: ready"
                :starting "engine: starting…"
                :error (str "engine: error" (when error (str " — " error)))
                "engine: unknown")]))

(defn- header []
  [:div {:style {:display "flex" :align-items "center" :justify-content "space-between"
                 :padding "10px 16px"}}
   [:div {:style {:display "flex" :align-items "center" :gap "10px"}}
    [:span {:style {:font-weight 600}} "murakumo-studio"]
    [engine-badge]]
   [ui/tab-bar tabs (:tab @state/state) {}]])

;; ---------------------------------------------------------------------------
;; Models tab

(defn- load-models! []
  (swap! state/state assoc-in [:models :loading?] true)
  (-> (client/list-models)
      (.then (fn [res]
               (swap! state/state update :models merge
                      {:items (:data res) :loading? false :error nil})))
      (.catch (fn [e]
                (swap! state/state update :models merge
                       {:loading? false :error (str e)})))))

(defn- rescan-models! []
  (swap! state/state assoc-in [:models :loading?] true)
  (-> (client/scan-local-models)
      (.then (fn [_] (load-models!)))
      (.catch (fn [e]
                (swap! state/state update :models merge
                       {:loading? false :error (str e)})))))

(defn- model-row [{:keys [id size_bytes context_length backend] :as m}]
  [ui/list-row
   [:div
    [:div {:style {:font-weight 600}} id]
    [:div {:style {:opacity 0.7 :font-size "12px"}}
     (fmt-bytes size_bytes) " · ctx " (or context_length "?") " · " (or backend "cpu")]]
   {:trailing (btn "Use in Chat"
                    #(swap! state/state assoc-in [:chat :model] id))}])

(defn- models-tab []
  (let [{:keys [items loading? error]} (:models @state/state)]
    [shape/panel
     [:div
      [:div {:style {:display "flex" :justify-content "space-between" :margin-bottom "8px"}}
       [:span "Local models (~/.murakumo-studio/models + ~/.ollama/models)"]
       [btn (if loading? "Scanning…" "Rescan") rescan-models! {:disabled? loading?}]]
      (when error [:div {:style {:color "#ff8a8a"}} (str error)])
      (if (empty? items)
        [:div {:style {:opacity 0.6}} "No models found yet. Try Rescan, or use the Download tab."]
        [ui/list-view (mapv model-row items)])]]))

;; ---------------------------------------------------------------------------
;; Chat tab

(defn- send-message! []
  (let [{:keys [model input messages]} (:chat @state/state)]
    (when (and model (seq (clojure.string/trim input)))
      (let [user-msg {:role "user" :content input}
            history (conj messages user-msg)]
        (swap! state/state update :chat merge {:messages history :input "" :busy? true :error nil})
        (-> (client/chat-completion
             {:model model
              :messages history
              :temperature (get-in @state/state [:settings :temperature])
              :max-tokens (get-in @state/state [:settings :max-tokens])})
            (.then (fn [res]
                     (let [reply (get-in res [:choices 0 :message])]
                       (swap! state/state update :chat merge
                              {:messages (conj history (or reply {:role "assistant" :content "(no reply)"}))
                               :busy? false}))))
            (.catch (fn [e]
                      (swap! state/state update :chat merge {:busy? false :error (str e)}))))))))

(defn- message-bubble [{:keys [role content]}]
  [:div {:class "ms-chat-log"
         :style {:margin "6px 0" :padding "8px 12px" :border-radius "10px"
                 :background (if (= role "user") "rgba(255,255,255,.08)" "rgba(120,170,255,.10)")}}
   [:div {:style {:opacity 0.6 :font-size "11px" :margin-bottom "2px"}} role]
   [:div content]])

(defn- chat-tab []
  (let [{:keys [model messages input busy? error]} (:chat @state/state)
        model-ids (mapv :id (get-in @state/state [:models :items]))]
    [shape/panel
     [:div
      [ui/menu-select (mapv (fn [id] [id id]) model-ids)
       {:value (or model "") :on-change #(swap! state/state assoc-in [:chat :model] (.. % -target -value))}]
      (when (empty? model-ids)
        [:div {:style {:opacity 0.6 :margin-top "6px"}} "No models loaded — pick one from the Models tab first."])
      [:div {:style {:margin "10px 0" :max-height "340px" :overflow-y "auto"}}
       (for [[i m] (map-indexed vector messages)]
         ^{:key i} [message-bubble m])]
      (when error [:div {:style {:color "#ff8a8a"}} (str error)])
      [ui/text-area {:value input :rows 3 :placeholder "Ask something…"
                      :on-input #(swap! state/state assoc-in [:chat :input] (.. % -target -value))}]
      [:div {:style {:margin-top "8px"}}
       [btn (if busy? "Generating…" "Send") send-message! {:disabled? (or busy? (not model))}]]]]))

;; ---------------------------------------------------------------------------
;; Download tab

(defn- download! []
  (let [{:keys [repo-id file]} (:download @state/state)]
    (swap! state/state update :download merge {:busy? true :error nil :done nil})
    (-> (client/download-model {:repo-id repo-id :file file})
        (.then (fn [res]
                 (swap! state/state update :download merge {:busy? false :done res})
                 (load-models!)))
        (.catch (fn [e]
                  (swap! state/state update :download merge {:busy? false :error (str e)}))))))

(defn- download-tab []
  (let [{:keys [repo-id file busy? error done]} (:download @state/state)]
    [shape/panel
     [:div
      [:div {:style {:opacity 0.7 :margin-bottom "8px"}}
       "Download a GGUF file from a Hugging Face Hub repo."]
      [ui/text-field {:value repo-id :placeholder "e.g. bartowski/gemma-4-e4b-it-GGUF"
                       :on-input #(swap! state/state assoc-in [:download :repo-id] (.. % -target -value))}]
      [ui/text-field {:value file :placeholder "e.g. gemma-4-e4b-it-Q4_K_M.gguf"
                       :on-input #(swap! state/state assoc-in [:download :file] (.. % -target -value))}]
      [:div {:style {:margin-top "8px"}}
       [btn (if busy? "Downloading…" "Download") download!
        {:disabled? (or busy? (empty? repo-id) (empty? file))}]]
      (when error [:div {:style {:color "#ff8a8a" :margin-top "6px"}} (str error)])
      (when done [:div {:style {:margin-top "6px"}} (str "Saved: " (:path done))])]]))

;; ---------------------------------------------------------------------------
;; Settings tab

(defn- num-field [path label]
  [:div {:style {:margin "6px 0"}}
   [:label {:style {:display "block" :opacity 0.7 :font-size "12px"}} label]
   [ui/text-field {:value (str (get-in @state/state path)) :type "number"
                    :on-input #(swap! state/state assoc-in path
                                       (js/parseFloat (.. % -target -value)))}]])

(defn- settings-tab []
  [shape/panel
   [:div
    [num-field [:settings :threads] "CPU threads"]
    [num-field [:settings :context-length] "Context length"]
    [num-field [:settings :temperature] "Temperature"]
    [num-field [:settings :max-tokens] "Max tokens per reply"]
    [ui/divider]
    [:div {:style {:opacity 0.7 :font-size "12px"}}
     "Compute backend: CPU (num.cpu, via kotoba-lang/inference). WebGPU/GPU "
     "offload is roadmapped (ADR-2607032700 Phase 2) — not selectable yet."]]])

;; ---------------------------------------------------------------------------
;; Fleet tab

(defn- load-fleet-status! []
  (-> (client/fleet-status)
      (.then (fn [res] (swap! state/state update :fleet merge {:status res :error nil})))
      (.catch (fn [e] (swap! state/state update :fleet assoc :error (str e))))))

(defn- toggle-fleet! []
  (let [enabled? (get-in @state/state [:fleet :enabled?])]
    (swap! state/state assoc-in [:fleet :busy?] true)
    (-> (if enabled? (client/fleet-leave) (client/fleet-join))
        (.then (fn [_]
                 (swap! state/state update :fleet merge {:enabled? (not enabled?) :busy? false})
                 (load-fleet-status!)))
        (.catch (fn [e] (swap! state/state update :fleet merge {:busy? false :error (str e)}))))))

(defn- fleet-tab []
  (let [{:keys [enabled? status busy? error]} (:fleet @state/state)]
    [shape/panel
     [:div
      [:div {:style {:opacity 0.7 :margin-bottom "8px"}}
       "Announce this node's local models to the murakumo fleet "
       "(gftdcojp/cloud-murakumo-fleet /infer/*). v1 is announce-only — "
       "compute participation is a follow-up (ADR-2607032700 Phase 3)."]
      [ui/toggle {:checked (boolean enabled?) :disabled busy?
                   :on-change toggle-fleet!}]
      [:span {:style {:margin-left "8px"}} (if enabled? "Joined" "Not joined")]
      (when error [:div {:style {:color "#ff8a8a" :margin-top "6px"}} (str error)])
      (when status [:pre {:style {:opacity 0.7 :font-size "11px" :margin-top "8px"}}
                     (pr-str status)])]]))

;; ---------------------------------------------------------------------------
;; root

(defn root []
  [:div {:on-click (on-tab-bar-click state/set-tab!)}
   [header]
   (case (:tab @state/state)
     :models [models-tab]
     :chat [chat-tab]
     :download [download-tab]
     :settings [settings-tab]
     :fleet [fleet-tab]
     [models-tab])])
