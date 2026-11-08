(ns metabase.remote-sync.core-test
  (:require
   [clojure.test :refer :all]
   [metabase.collections.test-utils :as collections.tu]
   [metabase.remote-sync.core :as remote-sync]
   [metabase.test :as mt]
   [toucan2.core :as t2]))

(defn- synced?
  "Whether the row of `model` with `pk` is synced."
  [model pk]
  (t2/exists? model {:where [:and [:= :id pk] [:in :id (remote-sync/synced-ids-clause model)]]}))

(deftest synced-models-test
  (testing "content models define which of their rows are synced"
    (is (every? (set (remote-sync/synced-models))
                [:model/Card :model/Collection :model/Dashboard :model/DashboardCard :model/Document
                 :model/NativeQuerySnippet :model/Timeline :model/Transform]))))

(deftest collection-content-test
  (mt/with-temp [:model/Collection    synced   {:name "Synced" :is_remote_synced true :location "/"}
                 :model/Collection    child    {:name "Child" :is_remote_synced true :location (format "/%d/" (:id synced))}
                 :model/Collection    unsynced {:name "Unsynced" :location "/"}
                 :model/Card          card     {:collection_id (:id child)}
                 :model/Card          archived {:collection_id (:id child) :archived true}
                 :model/Card          other    {:collection_id (:id unsynced)}
                 :model/Dashboard     dash     {:collection_id (:id synced)}
                 :model/DashboardCard dashcard {:dashboard_id (:id dash) :card_id (:id card)}]
    (testing "synced top-level collection and its descendants are synced, with their content"
      (is (synced? :model/Collection (:id synced)))
      (is (synced? :model/Collection (:id child)))
      (is (synced? :model/Card (:id card)))
      (is (synced? :model/Dashboard (:id dash)))
      (is (synced? :model/DashboardCard (:id dashcard))))
    (testing "archived content and content of unsynced collections is not synced"
      (is (not (synced? :model/Card (:id archived))))
      (is (not (synced? :model/Collection (:id unsynced))))
      (is (not (synced? :model/Card (:id other)))))))

(deftest library-content-test
  (mt/with-temp [:model/NativeQuerySnippet snippet {}]
    (testing "snippets are synced with the Library"
      (collections.tu/with-library-synced
        (is (synced? :model/NativeQuerySnippet (:id snippet)))))))

(deftest transforms-content-test
  (mt/with-temp [:model/Collection folder    {:name "Folder" :namespace "transforms" :location "/"}
                 :model/Transform  transform {:collection_id (:id folder)}]
    (testing "transforms and their folders are always synced"
      (is (synced? :model/Transform (:id transform)))
      (is (synced? :model/Collection (:id folder))))))

(deftest without-remote-sync-test
  (mt/with-premium-features #{}
    (mt/with-temp [:model/Collection synced {:name "Synced" :is_remote_synced true :location "/"}
                   :model/Card       card   {:collection_id (:id synced)}]
      (testing "without remote sync, every row can be written"
        (is (false? (remote-sync/read-only?)))
        (is (true? (remote-sync/can-write? :model/Card (:id card))))))))
