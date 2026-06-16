(ns aps.mutator-cli-test
  (:require [aps.cli-test-helper :as cli-helper]
            [aps.cli.gherkin-mutator :as cli]
            [clojure.test :refer [deftest is]]))

(deftest parses-durations
  (is (= 0 (#'cli/parse-duration-ms "")))
  (is (= 0 (#'cli/parse-duration-ms "bogus")))
  (is (= 12 (#'cli/parse-duration-ms "12ms")))
  (is (= 3000 (#'cli/parse-duration-ms "3s")))
  (is (= 120000 (#'cli/parse-duration-ms "2m"))))

(deftest parses-options
  (let [opts (#'cli/parse-args ["--feature" "f.feature"
                                "--work-dir" "work"
                                "--generated-dir" "gen"
                                "--workers" "4"
                                "--timeout" "9s"
                                "--status-interval" "0s"
                                "--level" "full"
                                "--runner-worker" "bb worker.clj"
                                "--implementation-hash" "impl"
                                "--json"])]
    (is (= "f.feature" (:feature opts)))
    (is (= "work" (:work-dir opts)))
    (is (= "gen" (:generated-dir opts)))
    (is (= 4 (:workers opts)))
    (is (= "9s" (:timeout opts)))
    (is (= "0s" (:status-interval opts)))
    (is (= "full" (:level opts)))
    (is (= "bb worker.clj" (:runner-worker opts)))
    (is (= "impl" (:implementation-hash opts)))
    (is (:json opts))))

(deftest rejects-unknown-option
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"unknown option --wat"
                        (#'cli/parse-args ["--wat"]))))

(deftest mutator-cli-prints-help
  (let [result (cli-helper/run-bb-task ["gherkin-mutator" "--help"])]
    (is (= 0 (:exit result)))
    (is (re-find #"usage: bb gherkin-mutator" (:output result)))
    (is (re-find #"--runner-worker <command>" (:output result)))
    (is (re-find #"--level full\\|hard\\|soft" (:output result)))
    (is (re-find #"Exit codes:" (:output result)))))

(deftest report-success-and-failure-predicates
  (is (#'cli/successful-report? {}))
  (is (#'cli/successful-report? {:summary {:Survived 0 :Errors 0}}))
  (is (not (#'cli/successful-report? {:summary {:Survived 1 :Errors 0}})))
  (is (#'cli/failed-report? {:summary {:Survived 0 :Errors 1}})))

(deftest validates-good-options
  (is (nil? (#'cli/validate-options! {:level "hard" :runner-worker "bb worker.clj"}))))

(deftest run-mutator-coordinates-core-functions
  (let [calls (atom [])]
    (with-redefs [aps.gherkin/parse-file (fn [path] (swap! calls conj [:parse path]) {:name "F" :scenarios []})
                  aps.mutation/resolve-implementation-hash (fn [& args] (swap! calls conj (into [:hash] args)) "impl")
                  aps.mutation/run (fn [cfg] (swap! calls conj [:run cfg]) {:summary {:Survived 0 :Errors 0} :results []})
                  aps.mutation/write-mutation-metadata! (fn [& args] (swap! calls conj (into [:metadata] args)))
                  aps.mutation/write-json-report! (fn [report] (swap! calls conj [:json report]))
                  aps.mutation/write-text-report! (fn [report] (swap! calls conj [:text report]))]
      (#'cli/run-mutator! {:feature "f.feature"
                           :work-dir "work"
                           :generated-dir nil
                           :workers 1
                           :level "hard"
                           :implementation-hash nil
                           :status-interval "0s"
                           :runner-worker "bb worker.clj"
                           :json true})
      (is (= :parse (ffirst @calls)))
      (is (some #(= :run (first %)) @calls))
      (is (some #(= :metadata (first %)) @calls))
      (is (some #(= :json (first %)) @calls)))))
