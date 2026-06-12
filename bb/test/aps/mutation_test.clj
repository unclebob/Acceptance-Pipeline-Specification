(ns aps.mutation-test
  (:require [aps.mutation :as mutation]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def mutation-feature
  {:name "Withdrawals"
   :background [{:keyword "Given"
                 :text "an account balance of <balance>"
                 :parameters ["balance"]}]
   :scenarios [{:name "Withdraw cash"
                :steps [{:keyword "When"
                         :text "the customer withdraws <amount>"
                         :parameters ["amount"]}]
                :examples [{"balance" "100"
                            "amount" "20"
                            "remaining" "80"}]}]})

(defn- temp-dir []
  (doto (java.nio.file.Files/createTempDirectory "aps-bb-test" (make-array java.nio.file.attribute.FileAttribute 0))
    (.toFile)))

(defn- worker-script [dir]
  (let [path (io/file dir "worker.clj")]
    (spit path
          (str "(require '[cheshire.core :as json])\n"
               "(doseq [line (line-seq (java.io.BufferedReader. *in*))]\n"
               "  (let [req (json/parse-string line true)\n"
               "        outcome (case (:id req)\n"
               "                  \"m1\" \"test_failure\"\n"
               "                  \"m2\" \"test_success\"\n"
               "                  \"infrastructure_error\")]\n"
               "    (println (json/generate-string {:id (:id req)\n"
               "                                   :outcome outcome\n"
               "                                   :output \"worker output\"\n"
               "                                   :error (if (= outcome \"infrastructure_error\") \"boom\" \"\")\n"
               "                                   :duration 1}))\n"
               "    (flush)))\n"))
    (str path)))

(deftest discovers-mutations-in-stable-example-key-order
  (let [mutations (mutation/discover mutation-feature)]
    (is (= ["m1" "m2" "m3"] (mapv :ID mutations)))
    (is (= ["$.scenarios[0].examples[0].amount"
            "$.scenarios[0].examples[0].balance"
            "$.scenarios[0].examples[0].remaining"]
           (mapv :Path mutations)))
    (is (= ["20" "100" "80"] (mapv :Original mutations)))
    (is (every? true? (map #(not= (:Original %) (:Mutated %)) mutations)))))

(deftest apply-mutation-preserves-original-feature
  (let [mutation (first (mutation/discover mutation-feature))
        mutated (mutation/apply-mutation mutation-feature mutation)]
    (is (= "20" (get-in mutation-feature [:scenarios 0 :examples 0 "amount"])))
    (is (= (:Mutated mutation) (get-in mutated [:scenarios 0 :examples 0 "amount"])))
    (is (= (:background mutation-feature) (:background mutated)))))

(deftest classify-runner-outcomes
  (let [mutation (first (mutation/discover mutation-feature))]
    (is (= "killed" (:Status (mutation/make-result mutation {:outcome "test_failure"}))))
    (is (= "survived" (:Status (mutation/make-result mutation {:outcome "test_success"}))))
    (is (= "error" (:Status (mutation/make-result mutation {:outcome "infrastructure_error"}))))))

(deftest run-uses-persistent-worker-protocol
  (let [dir (.toFile (temp-dir))
        worker (worker-script dir)
        report (mutation/run {:feature mutation-feature
                              :work-dir (str (io/file dir "work"))
                              :workers 2
                              :status-interval-ms 0
                              :runner-command ["bb" worker]})]
    (is (= {:Total 3 :Killed 1 :Survived 1 :Errors 1}
           (:summary report)))
    (is (= ["killed" "survived" "error"]
           (mapv :Status (:results report))))
    (is (= ["m1" "m2" "m3"]
           (mapv #(get-in % [:Mutation :ID]) (:results report))))
    (testing "mutation JSON files are written for the worker"
      (is (.exists (io/file dir "work" "base" "feature.json")))
      (is (.exists (io/file dir "work" "mutations" "m1" "feature.json"))))))
