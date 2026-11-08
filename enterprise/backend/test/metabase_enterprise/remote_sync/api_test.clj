(ns metabase-enterprise.remote-sync.api-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer :all]
   [metabase-enterprise.remote-sync.test-util :as remote-sync.tu]
   [metabase.test :as mt]
   [toucan2.core :as t2]))

(use-fixtures :each (fn [thunk] (mt/with-premium-features #{:remote-sync} (thunk))))

(defn- card-path!
  "The path of the YAML file of `card` at the tip of `branch` of the repository of `url`, or nil."
  [url branch card]
  (some #(when (str/includes? (remote-sync.tu/read-file! url branch %) (str "entity_id: " (:entity_id card))) %)
        (remote-sync.tu/files! url branch)))

(deftest endpoints-require-superuser-test
  (doseq [path ["ee/remote-sync/is-dirty" "ee/remote-sync/dirty" "ee/remote-sync/current-task"
                "ee/remote-sync/branches" "ee/remote-sync/has-remote-changes"]]
    (testing path
      (mt/user-http-request :rasta :get 403 path))))

(deftest settings-test
  (remote-sync.tu/with-repository [url]
    (mt/with-temporary-setting-values [remote-sync-url    nil
                                       remote-sync-token  nil
                                       remote-sync-branch nil
                                       remote-sync-type   :read-write]
      (mt/with-temp [:model/Collection collection {:name "To sync" :location "/"}]
        (testing "saving the settings checks the repository, syncs the default branch, and the given collections"
          (is (= {:success true}
                 (mt/user-http-request :crowberto :put 200 "ee/remote-sync/settings"
                                       {:remote-sync-url  url
                                        :remote-sync-type "read-write"
                                        :collections      {(:id collection) true}})))
          (is (= "main" (mt/user-http-request :crowberto :get 200 "setting/remote-sync-branch")))
          (is (true? (t2/select-one-fn :is_remote_synced :model/Collection :id (:id collection)))))
        (testing "a repository URL that is not HTTPS is refused"
          (is (re-find #"Invalid repository URL"
                       (mt/user-http-request :crowberto :put 400 "ee/remote-sync/settings"
                                             {:remote-sync-url "ftp://example.com/repo.git"}))))
        (testing "a branch the repository lacks is refused"
          (is (re-find #"Invalid branch name"
                       (mt/user-http-request :crowberto :put 400 "ee/remote-sync/settings"
                                             {:remote-sync-url url :remote-sync-branch "missing"}))))
        (testing "a blank URL turns remote sync off and stops syncing collections"
          (mt/user-http-request :crowberto :put 200 "ee/remote-sync/settings" {:remote-sync-url ""})
          (is (false? (mt/user-http-request :crowberto :get 200 "setting/remote-sync-enabled")))
          (is (false? (t2/select-one-fn :is_remote_synced :model/Collection :id (:id collection)))))))))

(deftest test-connection-test
  (remote-sync.tu/with-repository [url]
    (testing "a reachable repository passes"
      (is (= {:status "success"}
             (mt/user-http-request :crowberto :post 200 "ee/remote-sync/test-connection" {:remote-sync-url url}))))
    (testing "a repository that can't be reached fails"
      (mt/user-http-request :crowberto :post 400 "ee/remote-sync/test-connection"
                            {:remote-sync-url (str url "-missing")}))))

(deftest push-and-pull-test
  (remote-sync.tu/with-repository [url]
    (remote-sync.tu/with-remote-sync [url :read-write]
      (mt/with-model-cleanup [:model/RemoteSyncTask]
        (mt/with-temp [:model/Collection synced {:name "Synced" :is_remote_synced true :location "/"}
                       :model/Card       card   {:name "Synced card" :collection_id (:id synced)}]
          (testing "the synced content not pushed yet is dirty"
            (is (= {:is_dirty true} (mt/user-http-request :crowberto :get 200 "ee/remote-sync/is-dirty")))
            (is (=? {:dirty [{:id (:id card) :name "Synced card" :model "card" :sync_status "create"
                              :collection {:id (:id synced) :name "Synced" :effective_ancestors []}}]}
                    (update (mt/user-http-request :crowberto :get 200 "ee/remote-sync/dirty")
                            :dirty #(filterv (comp #{"card"} :model) %)))))
          (testing "a push commits it, after which nothing is dirty and the remote has no changes"
            (let [{:keys [task_id]} (mt/user-http-request :crowberto :post 200 "ee/remote-sync/export"
                                                          {:branch "main" :message "Push synced content"})]
              (is (=? {:id task_id :status "successful" :outcome {:kind "pushed"}}
                      (remote-sync.tu/wait-for-task! task_id))))
            (is (some? (card-path! url "main" card)))
            (is (= {:is_dirty false} (mt/user-http-request :crowberto :get 200 "ee/remote-sync/is-dirty")))
            (is (=? {:has_changes false} (mt/user-http-request :crowberto :get 200 "ee/remote-sync/has-remote-changes"))))
          (testing "a pull loads the changes of the remote"
            (let [path (card-path! url "main" card)]
              (remote-sync.tu/commit! url "main" {path (str/replace (remote-sync.tu/read-file! url "main" path)
                                                                    "name: Synced card" "name: Renamed remotely")}))
            (is (=? {:has_changes true} (mt/user-http-request :crowberto :get 200 "ee/remote-sync/has-remote-changes")))
            (let [{:keys [task_id]} (mt/user-http-request :crowberto :post 200 "ee/remote-sync/import" {:branch "main"})]
              (is (=? {:id task_id :status "successful" :outcome {:kind "pulled"}}
                      (remote-sync.tu/wait-for-task! task_id))))
            (is (= "Renamed remotely" (t2/select-one-fn :name :model/Card :id (:id card))))))))))

(deftest export-checks-branch-test
  (remote-sync.tu/with-repository [url]
    (remote-sync.tu/with-remote-sync [url :read-write]
      (testing "a push to a branch other than the synced one is refused"
        (is (=? {:branch_mismatch true :current_branch "main"}
                (mt/user-http-request :crowberto :post 409 "ee/remote-sync/export" {:branch "other"}))))
      (testing "a push is refused on a read-only instance"
        (mt/with-temporary-setting-values [remote-sync-type :read-only]
          (mt/user-http-request :crowberto :post 400 "ee/remote-sync/export" {:branch "main"}))))))

(deftest branches-test
  (remote-sync.tu/with-repository [url]
    (remote-sync.tu/with-remote-sync [url :read-write]
      (testing "the branches of the repository are listed"
        (is (= {:items ["main"]} (mt/user-http-request :crowberto :get 200 "ee/remote-sync/branches"))))
      (testing "a new branch starts at the synced version and is synced"
        (mt/user-http-request :crowberto :post 200 "ee/remote-sync/create-branch" {:name "feature"})
        (is (= {:items ["feature" "main"]} (mt/user-http-request :crowberto :get 200 "ee/remote-sync/branches")))
        (is (= "feature" (mt/user-http-request :crowberto :get 200 "setting/remote-sync-branch")))))))

(deftest stash-test
  (remote-sync.tu/with-repository [url]
    (remote-sync.tu/with-remote-sync [url :read-write]
      (mt/with-model-cleanup [:model/RemoteSyncTask]
        (mt/with-temp [:model/Collection synced {:name "Synced" :is_remote_synced true :location "/"}
                       :model/Card       card   {:name "Synced card" :collection_id (:id synced)}]
          (testing "stashing pushes the synced content to a new branch and syncs with it"
            (let [{:keys [task_id]} (mt/user-http-request :crowberto :post 200 "ee/remote-sync/stash"
                                                          {:new_branch "stashed" :message "Stash"})]
              (is (=? {:status "successful"} (remote-sync.tu/wait-for-task! task_id))))
            (is (some? (card-path! url "stashed" card)))
            (is (nil? (card-path! url "main" card)))
            (is (= "stashed" (mt/user-http-request :crowberto :get 200 "setting/remote-sync-branch")))))))))

(deftest cancel-without-task-test
  (testing "there is nothing to cancel when no task runs"
    (mt/with-model-cleanup [:model/RemoteSyncTask]
      (t2/delete! :model/RemoteSyncTask)
      (mt/user-http-request :crowberto :post 400 "ee/remote-sync/current-task/cancel"))))

(deftest dirty-with-unknown-base-version-test
  (remote-sync.tu/with-repository [url]
    (remote-sync.tu/with-remote-sync [url :read-write]
      (mt/with-model-cleanup [:model/RemoteSyncTask]
        (mt/with-temp [:model/Collection synced {:name "Synced" :is_remote_synced true :location "/"}
                       :model/Card       _      {:name "Synced card" :collection_id (:id synced)}
                       :model/RemoteSyncTask _  {:sync_task_type "export" :version "0000000000000000000000000000000000000000"
                                                 :started_at :%now :ended_at :%now}]
          (testing "all synced content is dirty when the repository lacks the version of the last sync"
            (is (= {:is_dirty true} (mt/user-http-request :crowberto :get 200 "ee/remote-sync/is-dirty")))
            (is (=? {:dirty [{:name "Synced card" :sync_status "create"}]}
                    (update (mt/user-http-request :crowberto :get 200 "ee/remote-sync/dirty")
                            :dirty #(filterv (comp #{"card"} :model) %))))))))))

(deftest settings-refuse-nested-collections-test
  (remote-sync.tu/with-repository [url]
    (remote-sync.tu/with-remote-sync [url :read-write]
      (mt/with-temp [:model/Collection parent {:name "Parent" :location "/"}
                     :model/Collection child  {:name "Child" :location (format "/%d/" (:id parent))}]
        (testing "only top-level collections can be synced"
          (mt/user-http-request :crowberto :put 400 "ee/remote-sync/settings" {:collections {(:id child) true}})
          (is (false? (t2/select-one-fn :is_remote_synced :model/Collection :id (:id child))))
          (mt/user-http-request :crowberto :put 200 "ee/remote-sync/settings" {:collections {(:id parent) true}})
          (is (true? (t2/select-one-fn :is_remote_synced :model/Collection :id (:id parent)))))))))
