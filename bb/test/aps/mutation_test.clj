(ns aps.mutation-test
  (:require [aps.mutation :as mutation]
            [aps.json :as aps-json]
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
    (is (= "error" (:Status (mutation/make-result mutation {:outcome "infrastructure_error"}))))
    (is (= 0 (:Duration (mutation/make-result mutation {}))))))

(deftest mutates-supported-value-shapes
  (is (= "false" (mutation/mutate-value "$.flag" "true")))
  (is (= "true" (mutation/mutate-value "$.flag" "false")))
  (is (= "value" (mutation/mutate-value "$.nil" "nil")))
  (is (not= "42" (mutation/mutate-value "$.int" "42")))
  (is (re-find #"\." (mutation/mutate-value "$.float" "3.14")))
  (is (str/includes? (mutation/mutate-value "$.list" "1, 2, 3") ", "))
  (is (= "x" (mutation/mutate-value "$.empty" "")))
  (is (= \A (#'mutation/dither-char \a)))
  (is (= \a (#'mutation/dither-char \A)))
  (is (= \x (#'mutation/dither-char \1))))

(deftest parses-and-mutates-numeric-edge-cases
  (is (= 7 (#'mutation/parse-int "7")))
  (is (nil? (#'mutation/parse-int "")))
  (is (nil? (#'mutation/parse-int "abc")))
  (is (= 7.5 (#'mutation/parse-float "7.5")))
  (is (nil? (#'mutation/parse-float "7")))
  (is (nil? (#'mutation/parse-float "NaN")))
  (is (= -1 (#'mutation/signed-delta 0 9)))
  (is (= 2 (#'mutation/signed-delta 1 9)))
  (is (= "8" (#'mutation/mutate-int 0 "9")))
  (is (str/starts-with? (#'mutation/mutate-float 0 "3.14") "2.")))

(deftest shifts-characters-by-case-boundary
  (is (= \A (#'mutation/shift-char \a \a \z \A)))
  (is (= \Z (#'mutation/shift-char \z \a \z \A)))
  (is (nil? (#'mutation/shift-char \0 \a \z \A))))

(deftest close-worker-destroys-live-process
  (let [worker (#'mutation/start-worker ["bb" "-e" "(Thread/sleep 5000)"])
        process (:process worker)]
    (#'mutation/close-worker! worker)
    (.waitFor process 500 java.util.concurrent.TimeUnit/MILLISECONDS)
    (is (false? (.isAlive process)))))

(deftest formats-status-lines
  (is (= {:Total 0 :Killed 0 :Survived 0 :Errors 0} (#'mutation/empty-summary)))
  (is (= 0 (#'mutation/completed {})))
  (is (= 6 (#'mutation/completed {:Killed 1 :Survived 2 :Errors 3})))
  (let [line (#'mutation/status-line (System/nanoTime) {:Total 2 :Killed 1 :Survived 0 :Errors 0} 1 0 0)
        elapsed (Long/parseLong (second (re-find #"elapsed=(\d+)ms" line)))]
    (is (< elapsed 10000))
    (is (str/includes? line "total=2 completed=1 running=1 killed=1")))
  (is (str/includes? (#'mutation/status-line 0 {:Total 2 :Killed 1 :Survived 0 :Errors 0} 0 1 3)
                     "skipped_scenarios=1 skipped_mutations=3")))

(deftest parses-worker-response-errors
  (let [started (System/nanoTime)
        request {:id "m1"}]
    (is (= "worker exited without response"
           (:error (#'mutation/parse-worker-response nil request started))))
    (is (str/includes? (:error (#'mutation/parse-worker-response "{\"id\":\"m2\",\"outcome\":\"test_success\"}" request started))
                       "does not match"))))

(deftest worker-response-defaults-duration-and-output-fields
  (let [started (System/nanoTime)
        response (#'mutation/parse-worker-response "{\"id\":\"m1\",\"outcome\":\"test_success\"}" {:id "m1"} started)]
    (is (= "test_success" (:outcome response)))
    (is (= "" (:output response)))
    (is (= "" (:error response)))
    (is (integer? (:duration response)))))

(deftest renders-text-report-details
  (let [mutation (first (mutation/discover mutation-feature))
        report {:summary {:Total 2 :Killed 0 :Survived 1 :Errors 1
                          :SkippedScenarios 1 :SkippedMutations 3}
                :results [(mutation/make-result mutation {:outcome "test_success"
                                                          :output "passed"})
                          (mutation/make-result mutation {:outcome "infrastructure_error"
                                                          :error "boom"
                                                          :output "logs"})]}
        text (#'mutation/report-text report)]
    (is (str/includes? text "skipped_scenarios=1 skipped_mutations=3"))
    (is (str/includes? text "output:\npassed"))
    (is (str/includes? text "error: boom"))
    (is (str/includes? text "output:\nlogs"))))

(deftest resolves-generated-implementation-hash
  (let [dir (.toFile (temp-dir))
        generated (io/file dir "generated")
        metadata-dir (io/file generated "metadata")
        metadata-file (io/file metadata-dir "features-sample-feature.json")]
    (.mkdirs metadata-dir)
    (spit metadata-file "{\"feature_path\":\"features/sample.feature\",\"implementation_hash\":\"abc\"}")
    (is (= "override" (mutation/resolve-implementation-hash (str generated) "features/sample.feature" "override")))
    (is (= "abc" (mutation/resolve-implementation-hash (str generated) "features/sample.feature" nil)))
    (is (= "unknown" (mutation/resolve-implementation-hash (str generated) "features/other.feature" nil)))))

(deftest manifest-hashes-match-canonical-json-shape
  (let [scenario-with-empty-parameters {:name "No parameters"
                                        :steps [{:keyword "Then"
                                                 :text "the cave is ready"
                                                 :parameters []}]
                                        :examples []}
        scenario-without-empty-parameters {:name "No parameters"
                                           :steps [{:keyword "Then"
                                                    :text "the cave is ready"}]
                                           :examples []}
        parameterized-scenario {:name "Parameterized"
                                :steps [{:keyword "Then"
                                         :text "room <room> is ready"
                                         :parameters ["room"]}]
                                :examples []}
        example-in-feature-order {"room" "1" "neighbors" "2, 5, 8"}
        example-in-canonical-json-order {"neighbors" "2, 5, 8" "room" "1"}]
    (is (= (#'mutation/hash-json scenario-with-empty-parameters)
           (#'mutation/hash-json scenario-without-empty-parameters)))
    (is (not= (#'mutation/hash-json scenario-without-empty-parameters)
              (#'mutation/hash-json (dissoc scenario-without-empty-parameters :examples))))
    (is (= (#'mutation/sha256 "{\"name\":\"Parameterized\",\"steps\":[{\"keyword\":\"Then\",\"text\":\"room \\u003croom\\u003e is ready\",\"parameters\":[\"room\"]}],\"examples\":[]}")
           (#'mutation/hash-json parameterized-scenario)))
    (is (= (#'mutation/hash-json example-in-feature-order)
           (#'mutation/hash-json example-in-canonical-json-order)))))

(deftest metadata-slug-keeps-digits-and-trims-hyphens
  (is (= "features-123-sample-feature"
         (#'mutation/feature-metadata-slug "/features/123 sample.feature!")))
  (is (= "file-9"
         (#'mutation/feature-metadata-slug "file 9"))))

(deftest skipped-summary-text-only-appears-for-positive-counts
  (is (nil? (#'mutation/skipped-summary-text {})))
  (is (nil? (#'mutation/skipped-summary-text {:SkippedScenarios 0 :SkippedMutations 0})))
  (is (= " skipped_scenarios=1 skipped_mutations=0"
         (#'mutation/skipped-summary-text {:SkippedScenarios 1 :SkippedMutations 0})))
  (is (= " skipped_scenarios=0 skipped_mutations=2"
         (#'mutation/skipped-summary-text {:SkippedScenarios 0 :SkippedMutations 2}))))

(deftest run-config-provides-worker-and-status-defaults
  (is (= 1 (:workers (#'mutation/run-config {}))))
  (is (= 30000 (:status-interval-ms (#'mutation/run-config {})))))

(deftest emit-status-writes-only-for-positive-interval
  (let [err (java.io.StringWriter.)]
    (binding [*err* err]
      (#'mutation/emit-status {:status-interval-ms 1} (System/nanoTime) {:Total 0 :Killed 0 :Survived 0 :Errors 0} 0 0 0))
    (is (str/includes? (str err) "status elapsed=")))
  (let [err (java.io.StringWriter.)]
    (binding [*err* err]
      (#'mutation/emit-status {:status-interval-ms 0} (System/nanoTime) {:Total 0 :Killed 0 :Survived 0 :Errors 0} 0 0 0))
    (is (= "" (str err)))))

(deftest writes-and-reuses-mutation-metadata
  (let [dir (.toFile (temp-dir))
        work-dir (str (io/file dir "work"))
        feature-path (str (io/file dir "sample.feature"))
        feature-content "Feature: Withdrawals\n\nScenario Outline: Withdraw cash\n  Then x is <amount>\n\nExamples:\n  | amount | balance | remaining |\n  | 20     | 100     | 80        |\n"
        feature mutation-feature
        mutations (mutation/discover feature)
        clean-report {:summary {:Total 3 :Killed 3 :Survived 0 :Errors 0}
                      :results (mapv #(mutation/make-result % {:outcome "test_failure"}) mutations)}]
    (spit feature-path feature-content)
    (mutation/write-mutation-metadata! work-dir feature-path feature clean-report "impl-a" "hard" true)
    (let [content (slurp feature-path)
          metadata-path (#'mutation/mutation-metadata-path work-dir feature-path)
          metadata (#'mutation/read-mutation-metadata work-dir feature-path)]
      (is (= feature-content content))
      (is (str/includes? (slurp metadata-path) "\"stamp\""))
      (is (= "Withdrawals" (get-in metadata [:manifest :feature_name])))
      (is (= #{0} (#'mutation/accepted-skips {:feature feature
                                              :feature-path feature-path
                                              :work-dir work-dir
                                              :level "hard"
                                              :implementation-hash "impl-a"}
                                             mutations)))
      (is (= #{} (#'mutation/accepted-skips {:feature feature
                                             :feature-path feature-path
                                             :work-dir work-dir
                                             :level "full"
                                             :implementation-hash "impl-a"}
                                            mutations)))
      (is (= #{} (#'mutation/accepted-skips {:feature feature
                                             :feature-path feature-path
                                             :work-dir work-dir
                                             :level "hard"
                                             :implementation-hash "impl-b"}
                                            mutations)))
      (is (= #{0} (#'mutation/accepted-skips {:feature feature
                                              :feature-path feature-path
                                              :work-dir work-dir
                                              :level "soft"
                                              :implementation-hash "impl-b"}
                                             mutations))))))

(deftest reads-legacy-stamp-only-mutation-metadata
  (let [dir (.toFile (temp-dir))
        feature-path (str (io/file dir "stamp.feature"))]
    (spit feature-path "# mutation-stamp: sha256=abc\n\nFeature: Stamp\n")
    (is (= {:stamp "abc" :manifest {}}
           (#'mutation/read-mutation-metadata (str (io/file dir "work")) feature-path)))))

(deftest scenario-summary-shortcut-and-path-parsing
  (is (= 0 (#'mutation/scenario-index-from-path "$.scenarios[0].examples[1].amount")))
  (is (nil? (#'mutation/scenario-index-from-path "$.background[0]")))
  (is (= 2 (#'mutation/mutation-count-for-scenario [{:scenario 0} {:scenario 1} {:scenario 0}] 0)))
  (is (= {0 {:Total 1 :Killed 1 :Survived 0 :Errors 0}}
         (#'mutation/scenario-summaries {:scenarios [{:name "Only"}]}
                                        {:summary {:Total 1 :Killed 1 :Survived 0 :Errors 0}
                                         :results []})))
  (is (= {}
         (#'mutation/scenario-summaries {:scenarios [{:name "Only"}]}
                                        {:summary {:Total 0 :Killed 0 :Survived 0 :Errors 0}
                                         :results []})))
  (is (= {}
         (#'mutation/scenario-summaries {:scenarios [{:name "Only"}]}
                                        {:summary {}
                                         :results []}))))

(deftest manifest-reuse-rejects-survivors-errors-and-out-of-range-indexes
  (let [feature mutation-feature
        mutations (mutation/discover feature)
        clean-report {:summary {:Total 3 :Killed 3 :Survived 0 :Errors 0}
                      :results (mapv #(mutation/make-result % {:outcome "test_failure"}) mutations)}
        current (#'mutation/new-manifest "f.feature" feature clean-report "impl")
        base-entry (assoc (first (:scenarios current)) :mutation_count (count mutations))
        nil-scenario-entry {:index 1
                            :name nil
                            :scenario_hash (#'mutation/hash-json nil)
                            :mutation_count (count mutations)
                            :result {}}]
    (is (not (#'mutation/manifest-entry-reusable? current current (assoc base-entry :index 99) "hard" feature mutations)))
    (is (not (#'mutation/manifest-entry-reusable? current current (assoc base-entry :index (count (:scenarios feature))) "hard" feature mutations)))
    (is (not (#'mutation/manifest-entry-reusable? current current nil-scenario-entry "hard" feature mutations)))
    (is (not (#'mutation/manifest-entry-reusable? current current (assoc-in base-entry [:result :Survived] 1) "hard" feature mutations)))
    (is (not (#'mutation/manifest-entry-reusable? current current (assoc-in base-entry [:result :Errors] 1) "hard" feature mutations)))
    (is (#'mutation/manifest-entry-reusable? current current (update base-entry :result dissoc :Survived :Errors) "hard" feature mutations))))

(deftest new-manifest-treats-missing-survivor-and-error-counts-as-zero
  (let [feature mutation-feature
        manifest (#'mutation/new-manifest "f.feature" feature
                                          {:summary {:Total 1}
                                           :results []}
                                          "impl")]
    (is (= [0] (mapv :index (:scenarios manifest))))))

(deftest validates-feature-mutation-stamp
  (let [dir (.toFile (temp-dir))
        work-dir (str (io/file dir "work"))
        feature-path (str (io/file dir "sample.feature"))
        feature mutation-feature
        mutations (mutation/discover feature)
        clean-report {:summary {:Total 3 :Killed 3 :Survived 0 :Errors 0}
                      :results (mapv #(mutation/make-result % {:outcome "test_failure"}) mutations)}]
    (spit feature-path "Feature: Withdrawals\n\nScenario Outline: Withdraw cash\n  Then x is <amount>\n\nExamples:\n  | amount | balance | remaining |\n  | 20     | 100     | 80        |\n")
    (is (false? (boolean (#'mutation/feature-stamp-valid? work-dir feature-path))))
    (mutation/write-mutation-metadata! work-dir feature-path feature clean-report "impl-a" "hard" true)
    (is (#'mutation/feature-stamp-valid? work-dir feature-path))
    (spit feature-path (str (slurp feature-path) "\n# changed\n"))
    (is (false? (boolean (#'mutation/feature-stamp-valid? work-dir feature-path))))))

(deftest ignores-malformed-mutation-metadata
  (let [dir (.toFile (temp-dir))
        feature-path (str (io/file dir "bad.feature"))]
    (spit feature-path "# acceptance-mutation-manifest-begin\n# not-json\n# acceptance-mutation-manifest-end\n\nFeature: Bad\n")
    (is (nil? (#'mutation/read-mutation-metadata (str (io/file dir "work")) feature-path)))))

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

(deftest run-coerces-zero-workers-to-one
  (let [dir (.toFile (temp-dir))
        worker (worker-script dir)
        report (mutation/run {:feature mutation-feature
                              :work-dir (str (io/file dir "work"))
                              :workers 0
                              :status-interval-ms 0
                              :runner-command ["bb" worker]})]
    (is (= 3 (get-in report [:summary :Total])))
    (is (= 3 (count (:results report))))))
