(ns metabase.remote-sync.core
  "Remote sync: which rows of each model it syncs, whether they can be written, and its settings."
  (:require
   [metabase.remote-sync.settings]
   [metabase.remote-sync.synced]
   [potemkin :as p]))

(comment
  metabase.remote-sync.settings/keep-me
  metabase.remote-sync.synced/keep-me)

(p/import-vars
 [metabase.remote-sync.synced
  can-write-clause
  can-write?
  read-only?
  synced-changes?
  synced-ids-clause
  synced-models
  synced-where-clause]
 [metabase.remote-sync.settings
  remote-sync-auto-import
  remote-sync-auto-import-rate
  remote-sync-branch
  remote-sync-enabled
  remote-sync-git-timeout-seconds
  remote-sync-task-time-limit-ms
  remote-sync-token
  remote-sync-type
  remote-sync-url])
