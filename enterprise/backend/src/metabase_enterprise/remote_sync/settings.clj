(ns metabase-enterprise.remote-sync.settings
  "The repository remote sync syncs with, saving its settings, and which collections it syncs."
  (:require
   [clojure.string :as str]
   [metabase-enterprise.remote-sync.db :as remote-sync.db]
   [metabase-enterprise.remote-sync.protocol :as protocol]
   [metabase-enterprise.remote-sync.protocol.git :as git]
   [metabase.collections.core :as collections]
   [metabase.settings.core :as setting]
   [metabase.util.i18n :refer [tru]]
   [toucan2.core :as t2]))

(set! *warn-on-reflection* true)

(defn repository
  "The repository of the remote sync settings."
  []
  (git/repository (setting/get :remote-sync-url) (setting/get :remote-sync-token)))

(defn- token
  "The token `settings` give, the saved one when they give none or give it obfuscated."
  [settings]
  (let [saved (setting/get :remote-sync-token)]
    (if (or (not (contains? settings :remote-sync-token))
            (= (:remote-sync-token settings) (setting/obfuscate-value saved)))
      saved
      (:remote-sync-token settings))))

(defn check-repository!
  "Throws unless the repository at the `:remote-sync-url` of `settings`, the saved one by default, can be reached with
  their token, and has their `:remote-sync-branch` when they give one."
  [settings]
  (let [url (or (:remote-sync-url settings) (setting/get :remote-sync-url))]
    (when (str/blank? url)
      (throw (ex-info (tru "Remote sync is not configured.") {})))
    (when-not (or (not (str/includes? url ":")) (re-find #"^(https?|file)://" url))
      (throw (ex-info (tru "Invalid repository URL: only HTTPS URLs are supported (e.g., https://git-host.example.com/yourcompany/repo.git)")
                      {})))
    (let [branches (protocol/branches (git/repository url (token settings)))
          branch   (:remote-sync-branch settings)]
      (when (and (not (str/blank? branch)) (not (some #{branch} branches)))
        (throw (ex-info (tru "Invalid branch name") {:branch branch}))))))

(def ^:private setting-keys
  [:remote-sync-url :remote-sync-token :remote-sync-type :remote-sync-branch :remote-sync-auto-import])

(defn save!
  "Saves the remote sync `settings`, except those set by environment variables, checking the repository first when they
  change it. A blank URL turns remote sync off; a missing branch is the default branch of the repository."
  [settings]
  (let [settings (into {} (remove #(= :env (setting/get-raw-value-source (key %)))) (select-keys settings setting-keys))]
    (if (and (contains? settings :remote-sync-url) (str/blank? (:remote-sync-url settings)))
      (do (run! #(setting/set! % nil) [:remote-sync-url :remote-sync-token :remote-sync-branch])
          (collections/clear-remote-synced-collection!))
      (do
        (when (some settings [:remote-sync-url :remote-sync-token :remote-sync-branch])
          (check-repository! settings))
        (t2/with-transaction [_conn]
          (doseq [[k v] (assoc settings :remote-sync-token (token settings))]
            (setting/set! k v)))
        (when (and (setting/get :remote-sync-url) (str/blank? (setting/get :remote-sync-branch)))
          (setting/set! :remote-sync-branch (protocol/default-branch (repository))))))))

(defn set-synced-collections!
  "Syncs the top-level collections whose id `collections` maps to true, and their descendants, and stops syncing those
  it maps to false."
  [collections]
  (when (seq collections)
    (when (= :read-only (setting/get :remote-sync-type))
      (throw (ex-info (tru "Cannot change synced collections when remote-sync-type is read-only.") {:status-code 400})))
    (t2/with-transaction [_conn]
      (doseq [[id synced?] collections
              :let [collection (remote-sync.db/collection id)]]
        (when-not (and collection (= "/" (:location collection)) (nil? (:personal_owner_id collection)))
          (throw (ex-info (tru "Only top-level collections that are not personal can be synced.")
                          {:status-code 400 :collection-id id})))
        (collections/set-remote-synced! collection synced?)))))
