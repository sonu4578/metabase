(ns metabase.remote-sync.synced
  "Which rows of each model remote sync syncs, as a HoneySQL where-clause each model defines, and whether they can be
  written, which synced rows can't on a read-only instance."
  (:require
   [metabase.remote-sync.db :as remote-sync.db]
   [metabase.remote-sync.schema :as remote-sync.schema]
   [metabase.remote-sync.settings :as remote-sync.settings]
   [metabase.serialization.core :as serialization]
   [metabase.util :as u]
   [metabase.util.malli :as mu]
   [toucan2.core :as t2]))

(set! *warn-on-reflection* true)

(defmulti synced-where-clause
  "The HoneySQL where-clause over the columns of `model`'s table, aliased `alias`, selecting the rows remote sync
  syncs."
  {:arglists '([model alias])}
  (fn [model _alias] model))

(defmethod synced-where-clause :default
  [_model _alias]
  [:= 1 0])

(defn synced-models
  "The models that define which of their rows remote sync syncs."
  []
  (sort (remove #{:default} (keys (methods synced-where-clause)))))

(mu/defn synced-ids-clause :- [:fn map?]
  "The HoneySQL query selecting the primary keys of the rows of `model` remote sync syncs."
  [model :- :keyword]
  (let [table (t2/table-name model)
        alias (keyword (str "synced_" (name table)))]
    ^:allow-subquery {:select [(u/qualified-key alias (first (t2/primary-keys model)))]
                      :from   [[table alias]]
                      :where  (synced-where-clause model alias)}))

(defn read-only?
  "Whether remote sync is on and keeps the rows it syncs read-only."
  []
  (boolean (and (remote-sync.settings/remote-sync-enabled)
                (= :read-only (remote-sync.settings/remote-sync-type)))))

(mu/defn can-write? :- :boolean
  "Whether the row of `model` with the primary key `pk` can be written, which a synced row can't on a read-only
  instance."
  [model :- :keyword
   pk    :- ::remote-sync.schema/pk]
  (or (not (read-only?))
      (not (remote-sync.db/row-matches? model :row pk (synced-where-clause model :row)))))

(mu/defn synced-changes? :- :boolean
  "Whether `changes` to `instance` of `model` change a column an export of it holds."
  [model    :- :keyword
   instance :- [:fn map?]
   changes  :- [:fn map?]]
  (boolean (some (fn [[k v]] (not= v (get instance k)))
                 (select-keys changes (serialization/columns model)))))

(mu/defn can-write-clause :- [:fn vector?]
  "The HoneySQL where-clause over the columns of `model`'s table, aliased `alias`, selecting the rows that can be
  written (see [[can-write?]])."
  [model :- :keyword
   alias :- :keyword]
  (if (read-only?)
    (let [pk (first (t2/primary-keys model))]
      [:not [:exists ^:allow-subquery {:select [[[:inline 1] :one]]
                                       :from   [[(t2/table-name model) :synced_row]]
                                       :where  [:and
                                                [:= (u/qualified-key :synced_row pk) (u/qualified-key alias pk)]
                                                (synced-where-clause model :synced_row)]}]])
    [:= 1 1]))
