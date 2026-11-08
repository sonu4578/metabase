(ns metabase-enterprise.remote-sync.init
  (:require
   [metabase-enterprise.remote-sync.settings]
   [metabase.collections.core :as collections]
   [metabase.remote-sync.core :as remote-sync]
   [metabase.startup.core :as startup]
   [metabase.util.log :as log]))

(set! *warn-on-reflection* true)

(defn- remote-sync-init!
  "Stops syncing collections when remote sync is off."
  []
  (when (and (not (remote-sync/remote-sync-enabled)) (collections/has-remote-synced-collection?))
    (log/info "Remote sync is off: no collection is synced any more")
    (collections/clear-remote-synced-collection!)))

(defmethod startup/def-startup-logic! ::remote-sync-setup
  [_]
  (remote-sync-init!))
