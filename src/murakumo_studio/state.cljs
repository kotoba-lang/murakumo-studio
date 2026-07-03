(ns murakumo-studio.state
  (:require [reagent.core :as r]))

(defonce state
  (r/atom
   {:tab :models
    :engine {:status :starting :error nil}   ; :starting :ready :error
    :models {:items [] :loading? false :error nil}
    :chat {:model nil :messages [] :input "" :busy? false :error nil}
    :download {:repo-id "" :file "" :busy? false :error nil :done nil}
    :settings {:threads 4 :context-length 2048 :temperature 0.7 :max-tokens 256}
    :fleet {:enabled? false :status nil :busy? false :error nil}}))

(defn set-tab! [tab]
  (swap! state assoc :tab tab))
