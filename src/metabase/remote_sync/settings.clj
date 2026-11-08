(ns metabase.remote-sync.settings
  "The settings of remote sync, which syncs content with a git repository."
  (:require
   [metabase.settings.core :as setting :refer [defsetting]]
   [metabase.util.i18n :refer [deferred-tru]]))

(set! *warn-on-reflection* true)

(defsetting remote-sync-enabled
  (deferred-tru "Is Git sync currently enabled?")
  :feature :remote-sync
  :type :boolean
  :visibility :authenticated
  :export? false
  :setter :none
  :getter (fn [] (some? (setting/get :remote-sync-url)))
  :audit :never
  :doc false)

(defsetting remote-sync-branch
  (deferred-tru "The remote branch to sync with, e.g. `main`")
  :feature :remote-sync
  :type :string
  :visibility :admin
  :encryption :when-encryption-key-set
  :export? false
  :can-read-from-env? true)

(defsetting remote-sync-token
  (deferred-tru "An Authorization Bearer token allowing access to the git repo over HTTP")
  :feature :remote-sync
  :type :string
  :visibility :admin
  :doc true
  :export? false
  :sensitive? true
  :encryption :when-encryption-key-set
  :audit :getter
  :can-read-from-env? true)

(defsetting remote-sync-url
  (deferred-tru "The location of your git repository, e.g. `https://github.com/acme-inco/metabase.git`")
  :feature :remote-sync
  :type :string
  :visibility :admin
  :encryption :when-encryption-key-set
  :export? false
  :can-read-from-env? true)

(defsetting remote-sync-type
  (deferred-tru "Git synchronization type - :read-write or :read-only")
  :feature :remote-sync
  :type :keyword
  :visibility :authenticated
  :export? false
  :encryption :no
  :default :read-only
  :setter (fn [new-value]
            (let [new-value (or new-value :read-only)
                  valid-types #{:read-only :read-write}
                  value (or (valid-types (keyword new-value))
                            (throw (ex-info "Remote-sync-type set to an unsupported value"
                                            {:value new-value
                                             :options (seq valid-types)})))]
              (setting/set-value-of-type! :keyword :remote-sync-type value)))
  :can-read-from-env? true)

(defsetting remote-sync-auto-import
  (deferred-tru "Whether to automatically import from the remote git repository. Only applies if remote-sync-type is :read-only.")
  :feature :remote-sync
  :type :boolean
  :visibility :authenticated
  :export? false
  :encryption :no
  :default false)

(defsetting remote-sync-auto-import-rate
  (deferred-tru "If remote-sync-type is :read-only and remote-sync-auto-import is true, the rate (in minutes) at which to check for updates to import. Defaults to 5.")
  :feature :remote-sync
  :type :integer
  :visibility :authenticated
  :export? false
  :encryption :no
  :default 5)

(defsetting remote-sync-task-time-limit-ms
  (deferred-tru "How long a remote sync task may go without proving its process is alive (via heartbeat, or progress on rows without one) before it is treated as dead and superseded. A slow but live task is never affected.")
  :feature :remote-sync
  :type :integer
  :visibility :authenticated
  :export? false
  :encryption :no
  :default (* 1000 60 5))

(defsetting remote-sync-git-timeout-seconds
  (deferred-tru "Network timeout (in seconds) for remote git operations such as fetch, push, clone, and ls-remote. A stalled connection would otherwise hang a sync indefinitely.")
  :feature :remote-sync
  :type :integer
  :visibility :authenticated
  :export? false
  :encryption :no
  :default 60)
