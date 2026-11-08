(ns metabase.remote-sync.db
  "Application database queries for the remote sync module."
  (:require
   [metabase.remote-sync.schema :as remote-sync.schema]
   [metabase.util :as u]
   [metabase.util.malli :as mu]
   [toucan2.core :as t2]))

(mu/defn row-matches? :- :boolean
  "Whether the row of `model` with the primary key `pk` matches `clause`, a HoneySQL where-clause over its table aliased
  `alias`."
  [model  :- :keyword
   alias  :- :keyword
   pk     :- ::remote-sync.schema/pk
   clause :- [:fn vector?]]
  (boolean (seq (t2/query {:select [[[:inline 1] :one]]
                           :from   [[(t2/table-name model) alias]]
                           :where  [:and [:= (u/qualified-key alias (first (t2/primary-keys model))) pk] clause]
                           :limit  1}))))
