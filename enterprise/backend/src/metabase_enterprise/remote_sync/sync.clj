(ns metabase-enterprise.remote-sync.sync
  "Push and pull. Both compare the documents of the tree the app DB last synced with (base), of the app DB (ours) and
  of the tip of the branch (theirs): a push commits what the merge of ours and theirs keeps, and a pull loads it into
  the app DB, deleting the synced rows it drops."
  (:require
   [clojure.string :as str]
   [metabase-enterprise.remote-sync.content :as content]
   [metabase-enterprise.remote-sync.db :as remote-sync.db]
   [metabase-enterprise.remote-sync.merge :as remote-sync.merge]
   [metabase-enterprise.remote-sync.protocol :as protocol]
   [metabase.models.interface :as mi]
   [metabase.remote-sync.core :as remote-sync]
   [metabase.serialization.core :as serialization]))

(set! *warn-on-reflection* true)

(defn state
  "What a push or pull of `branch` of `repo` compares, with `base-version` the version of the tree the app
  DB last synced with: the `:tip` version, and the `:base`, `:ours` and `:theirs` documents."
  [repo branch base-version]
  (protocol/fetch! repo)
  (let [tip    (or (protocol/version repo branch)
                   (throw (ex-info (format "The branch %s does not exist." branch) {:branch branch})))
        known? (and base-version (protocol/has-version? repo base-version))
        base   (content/tree-documents repo (when known? base-version))
        theirs (content/tree-documents repo tip)
        ours   (content/local-documents)]
    {:tip                tip
     :base               base
     :ours               ours
     :theirs             theirs
     :remote-changed?    (not (content/same-documents? base theirs))
     :history-rewritten? (and (some? base-version) (not known?))}))

(defn- changed-keys
  "The keys of the entities whose documents differ between `a` and `b`, maps from key to document."
  [a b]
  (filterv #(not (content/same? (get a %) (get b %)))
           (into (set (keys a)) (keys b))))

(defn- files
  "The content of the files of `docs` by path."
  [docs]
  (into {} (mapcat content/files) (vals docs)))

(defn- commit!
  "Commits the files of the `merged` documents in place of those of the `theirs` documents of the tip of `branch`,
  returning the new version, or nil when no file changes."
  [repo branch tip theirs merged message]
  (let [current (files theirs)
        desired (files merged)
        changes (merge (into {} (remove (fn [[path content]] (= content (get current path)))) desired)
                       (into {} (comp (remove desired) (map #(vector % nil))) (keys current)))]
    (when (seq changes)
      (protocol/commit! repo branch tip changes message))))

(defn- delete-rows!
  "Deletes the rows `ours` exports the documents with the keys `ks` from, each after the rows that depend on it."
  [ours ks]
  (binding [mi/*deserializing?* true]
    (doseq [{:keys [model keys]} (reverse (serialization/load-order ks #(mapv (comp :entity ours) %)))]
      (remote-sync.db/delete-rows! model (mapv (comp second :row ours) keys)))))

(defn- apply!
  "Makes the app DB, whose documents are `ours`, hold the `merged` documents, returning how many entities changed."
  [ours merged]
  (let [ks      (changed-keys ours merged)
        loaded  (filterv #(contains? merged %) ks)
        removed (filterv #(not (contains? merged %)) ks)]
    (serialization/with-import-transaction
      (when (seq loaded)
        (serialization/load-all loaded #(mapv (comp :entity merged) %)))
      (when (seq removed)
        (delete-rows! ours removed)))
    (count ks)))

(defn- missing-dependencies
  "What the entities of the `docs` whose keys `referrer?` holds for depend on that is of a synced model but not among
  the `docs`, for people."
  ([docs]
   (missing-dependencies docs (constantly true)))
  ([docs referrer?]
   (let [synced?       (set (remote-sync/synced-models))
         by-model      (reduce-kv (fn [m [model] doc] (update m model (fnil conj []) (:entity doc))) {} docs)]
     (for [[[model ref] referrers] (serialization/import-escapes by-model)
           :let  [referrers (filter referrer? referrers)]
           :when (and (synced? model) (seq referrers))]
       (format "%s uses %s %s, which is not synced."
               (str/join ", " (map #(remote-sync.merge/label % (get docs %)) referrers))
               (name model)
               (if (sequential? ref) (str/join "." ref) ref))))))

(defn- no-report
  "A progress reporter that reports nothing."
  [& _]
  nil)

(defn- conflict
  "The result of a push or pull that the `conflicts` labels stop."
  [conflicts]
  {:status    :conflict
   :conflicts conflicts
   :message   "The same content was changed both locally and on the remote branch."})

(defn push!
  "Pushes the content of the app DB synced to `branch` of `repo`, with the commit `message`. When the remote
  changed since `base-version`, overwrites it when `force?`, merges it in, also into the app DB, when `merge?`, and
  stops otherwise."
  [repo branch base-version {:keys [force? merge? message report] :or {report no-report}}]
  (let [{:keys [tip base ours theirs remote-changed?]} (state repo branch base-version)
        {:keys [merged conflicts]} (if (and merge? (not force?))
                                     (remote-sync.merge/merge-documents base ours theirs)
                                     {:merged ours})
        missing                    (missing-dependencies merged)]
    (cond
      (and remote-changed? (not force?) (not merge?))
      {:status    :conflict
       :conflicts ["The remote branch changed since the last sync."]
       :outcome   {:kind "remote-changed"}}

      (seq conflicts)
      (conflict conflicts)

      (seq missing)
      {:status  :error
       :message (str/join " " missing)}

      :else
      (let [_       (report 0.5 {:force? true})
            version (serialization/with-import-transaction
                      (when merge?
                        (apply! ours merged))
                      (commit! repo branch tip theirs merged message))]
        {:status  :success
         :version (or version tip)
         :outcome (if version
                    {:kind "pushed" :count (count (changed-keys theirs merged))}
                    {:kind "push-skipped"})}))))

(defn pull!
  "Pulls the content of `branch` of `repo` into the app DB, keeping its changes since `base-version` unless `force?`,
  and stops when the kept changes depend on content the pull removes."
  [repo branch base-version {:keys [force? report] :or {report no-report}}]
  (let [{:keys [tip base ours theirs]} (state repo branch base-version)
        {:keys [merged conflicts]} (if force?
                                     {:merged theirs}
                                     (remote-sync.merge/merge-documents base ours theirs))
        missing                    (when-not force?
                                     (missing-dependencies merged #(not (content/same? (get ours %) (get base %)))))]
    (cond
      (seq conflicts)
      (conflict conflicts)

      (seq missing)
      {:status  :error
       :message (str/join " " missing)}

      :else
      (do (report 0.5 {:force? true})
          {:status  :success
           :version tip
           :outcome {:kind "pulled" :count (apply! ours merged)}}))))

(defn local-changes
  "The `{:key :status :doc}` of each synced entity of the app DB that changed since the `base` documents of the tree it
  last synced with: `create`, `update`, or `delete` for one `ours` lacks, with the document of the side that has it."
  [base ours]
  (concat (for [[k doc] ours
                :when (not (content/same? doc (get base k)))]
            {:key k :status (if (contains? base k) "update" "create") :doc doc})
          (for [[k doc] base
                :when (not (contains? ours k))]
            {:key k :status "delete" :doc doc})))
