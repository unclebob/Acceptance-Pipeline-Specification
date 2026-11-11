(ns aps.heldout-test
  (:require [aps.heldout :as heldout]
            [clojure.test :refer [deftest is testing]]))

(def sample-feature
  {:name "Withdrawals"
   :background [{:keyword "Given" :text "an account" :parameters ["account"]}]
   :scenarios [{:name "withdraw cash" :steps []}
               {:name "deposit cash" :steps []}
               {:name "check balance" :steps []}
               {:name "block overdraft" :steps []}
               {:name "close account" :steps []}]})

(deftest split-is-deterministic-and-disjoint
  (let [{:keys [visible heldout]}
        (heldout/split-scenarios sample-feature 0.2 "seed-a")]
    (is (= 4 (count (:scenarios visible))))
    (is (= 1 (count (:scenarios heldout))))
    (is (empty? (clojure.set/intersection
                 (set (map :name (:scenarios visible)))
                 (set (map :name (:scenarios heldout))))))
    (is (= "Withdrawals" (:name visible) (:name heldout)))
    (is (= (:background sample-feature) (:background visible) (:background heldout)))
    (testing "same seed gives the same split"
      (is (= (heldout/split-scenarios sample-feature 0.2 "seed-a")
             (heldout/split-scenarios sample-feature 0.2 "seed-a"))))
    (testing "different seeds pick different held-out scenarios"
      (let [heldout-names (fn [seed]
                            (->> (heldout/split-scenarios sample-feature 0.2 seed)
                                 :heldout
                                 :scenarios
                                 (map :name)
                                 set))]
        (is (> (count (distinct (map heldout-names ["seed-a" "seed-b" "seed-c" "seed-d" "seed-e"]))) 1))))))

(deftest split-preserves-original-order-within-subsets
  (let [names (mapv :name (:scenarios sample-feature))
        {:keys [visible heldout]} (heldout/split-scenarios sample-feature 0.4 "seed-a")
        visible-names (map :name (:scenarios visible))
        heldout-names (map :name (:scenarios heldout))]
    (is (= (sort names) (sort (concat visible-names heldout-names))))
    (is (= visible-names (filter (set visible-names) names)))
    (is (= heldout-names (filter (set heldout-names) names)))))

(deftest split-rejects-invalid-inputs
  (testing "duplicate scenario names"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"unique"
                          (heldout/split-scenarios
                           {:name "F" :scenarios [{:name "a"} {:name "a"}]} 0.5 "s"))))
  (testing "too few scenarios"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"at least 2"
                          (heldout/split-scenarios
                           {:name "F" :scenarios [{:name "a"}]} 0.5 "s"))))
  (testing "fraction out of range"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"between 0 and 1"
                          (heldout/split-scenarios sample-feature 1.0 "s"))))
  (testing "fraction that empties one side"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"at least one scenario on each side"
                          (heldout/split-scenarios
                           {:name "F" :scenarios [{:name "a"} {:name "b"}]} 0.6 "s")))))

(defn results [& [override]]
  (merge {:results [{:name "a" :status "pass"}
                    {:name "b" :status "pass"}
                    {:name "c" :status "fail"}
                    {:name "d" :status "error"}]}
         override))

(deftest gate-computes-generalization-gap
  (let [visible {:results [{:name "a" :status "pass"}
                           {:name "b" :status "pass"}
                           {:name "c" :status "pass"}
                           {:name "d" :status "pass"}]}
        strong {:results [{:name "e" :status "pass"}
                          {:name "f" :status "pass"}
                          {:name "g" :status "pass"}
                          {:name "h" :status "pass"}]}
        weak {:results [{:name "e" :status "pass"}
                        {:name "f" :status "fail"}
                        {:name "g" :status "fail"}
                        {:name "h" :status "pass"}]}
        generalizing (heldout/gate-report visible strong 0.05)
        memorizing (heldout/gate-report visible weak 0.05)]
    (is (= {:total 4 :pass 4 :fail 0 :error 0} (:visible generalizing)))
    (is (= {:total 4 :pass 4 :fail 0 :error 0} (:heldout generalizing)))
    (is (= 0.0 (:gap generalizing)))
    (is (= "pass" (:status generalizing)))
    (is (= 0.5 (:gap memorizing)))
    (is (= 50.0 (:gap-pp memorizing)))
    (is (= "fail" (:status memorizing)))
    (testing "gap exactly at the limit passes"
      (is (= "pass" (:status (heldout/gate-report
                              visible
                              {:results [{:name "e" :status "pass"}
                                         {:name "f" :status "pass"}
                                         {:name "g" :status "pass"}
                                         {:name "h" :status "fail"}]}
                              0.25)))))))

(deftest gate-rejects-invalid-inputs
  (testing "empty results"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"empty"
                          (heldout/gate-report {:results []} {:results []} 0.05))))
  (testing "invalid status"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"invalid status"
                          (heldout/gate-report
                           {:results [{:name "a" :status "skipped"}]}
                           (results)
                           0.05))))
  (testing "negative max gap"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"not be negative"
                          (heldout/gate-report (results) (results) -0.1)))))

(deftest manifest-roundtrip-and-verify
  (let [{:keys [heldout]} (heldout/split-scenarios sample-feature 0.2 "seed-a")
        manifest (heldout/build-manifest sample-feature 0.2 "seed-a" heldout)]
    (is (= 1 (:version manifest)))
    (is (= "Withdrawals" (:feature manifest)))
    (is (= 1 (count (:scenarios manifest))))
    (testing "unchanged held-out set verifies"
      (is (= {:version 1 :status "verified" :expected 1 :actual 1
              :missing 0 :unknown 0 :changed 0}
             (heldout/verify-manifest manifest heldout))))
    (testing "a changed step is detected as drift"
      (let [tampered (update-in heldout [:scenarios 0 :steps]
                                (fnil conj [])
                                {:keyword "Then" :text "sneaky assertion"})]
        (is (= "drift" (:status (heldout/verify-manifest manifest tampered))))
        (is (= 1 (:changed (heldout/verify-manifest manifest tampered))))))
    (testing "an extra scenario is unknown drift"
      (let [grown (update heldout :scenarios conj {:name "new scenario" :steps []})]
        (is (= "drift" (:status (heldout/verify-manifest manifest grown))))
        (is (= 1 (:unknown (heldout/verify-manifest manifest grown))))))))

(deftest fingerprint-is-portable-and-stable
  (let [scenario {:name "withdraw"
                  :steps [{:keyword "When" :text "withdraw 20" :parameters []}]
                  :examples [{"balance" "100" "amount" "20"}]}]
    (is (= (heldout/scenario-fingerprint "seed-a" scenario)
           (heldout/scenario-fingerprint "seed-a" scenario)))
    (is (not= (heldout/scenario-fingerprint "seed-a" scenario)
              (heldout/scenario-fingerprint "seed-b" scenario)))
    (is (= "scenario\u0000withdraw\u0000step\u0000When\u0000withdraw 20\u0000\u0000example\u0000amount=20\u0000balance=100"
           (heldout/canonical-scenario-string scenario)))))

(deftest git-repo-guard-detects-repositories
  (let [tmp (System/getProperty "java.io.tmpdir")
        repo-dir (java.io.File. tmp (str "aps-guard-test-" (System/nanoTime)))]
    (try
      (.mkdirs (java.io.File. repo-dir ".git"))
      (is (heldout/inside-git-repo?
           (str (java.io.File. (java.io.File. repo-dir "heldout") "h.json"))))
      (is (heldout/inside-git-repo? (str (.getAbsolutePath (java.io.File. repo-dir "h.json")))))
      (is (not (heldout/inside-git-repo?
                (str (java.io.File. (java.io.File. tmp "aps-no-repo-here") "h.json")))))
      (finally
        (run! #(-> ^java.io.File % .delete)
              (reverse (file-seq repo-dir)))))))
