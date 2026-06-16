(ns aps.gherkin-test
  (:require [aps.cli-test-helper :as cli-helper]
            [aps.cli.gherkin-parser :as parser-cli]
            [aps.gherkin :as gherkin]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]))

(def sample-feature
  "
Feature: Withdrawals

Background:
  Given an account balance of <balance>

Scenario Outline: Withdraw cash
  When the customer withdraws <amount>
  Then the remaining balance is <remaining>

Examples:
  | balance | amount | remaining |
  | 100     | 20     | 80        |
  | 50      | 5      | 45        |
")

(deftest parses-feature-with-background-scenario-outline-and-examples
  (let [feature (gherkin/parse-string sample-feature)]
    (is (= "Withdrawals" (:name feature)))
    (is (= [{:keyword "Given"
             :text "an account balance of <balance>"
             :parameters ["balance"]}]
           (:background feature)))
    (is (= "Withdraw cash" (get-in feature [:scenarios 0 :name])))
    (is (= [{:keyword "When"
             :text "the customer withdraws <amount>"
             :parameters ["amount"]}
            {:keyword "Then"
             :text "the remaining balance is <remaining>"
             :parameters ["remaining"]}]
           (get-in feature [:scenarios 0 :steps])))
    (is (= [{"balance" "100" "amount" "20" "remaining" "80"}
            {"balance" "50" "amount" "5" "remaining" "45"}]
           (get-in feature [:scenarios 0 :examples])))))

(deftest rejects-invalid-feature-shapes
  (testing "missing Feature declaration"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"missing feature declaration"
                          (gherkin/parse-string "Scenario: orphan\n  Given something\n"))))
  (testing "examples outside scenario"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"examples outside scenario"
                          (gherkin/parse-string "Feature: Bad\n\nExamples:\n  | x |\n  | y |\n"))))
  (testing "example row cell mismatch"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"line 6: example row has 1 cells, header has 2"
                          (gherkin/parse-string "Feature: Bad\nScenario Outline: mismatch\n  Given <x>\nExamples:\n  | x | y |\n  | 1 |\n")))))

(deftest parser-cli-writes-json
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory "aps-parser-cli" (make-array java.nio.file.attribute.FileAttribute 0)))
        input (io/file dir "sample.feature")
        output (io/file dir "sample.json")]
    (spit input sample-feature)
    (parser-cli/-main (str input) (str output))
    (is (= "Withdrawals" (:name (json/parse-string (slurp output) true))))))

(deftest parser-cli-exits-nonzero-on-parse-error
  (let [result (cli-helper/run-bb-task ["gherkin-parser" "missing.feature" "/tmp/out.json"])]
    (is (= 1 (:exit result)))
    (is (re-find #"No such file|missing.feature" (:output result)))))

(deftest parser-cli-prints-help
  (let [result (cli-helper/run-bb-task ["gherkin-parser" "--help"])]
    (is (= 0 (:exit result)))
    (is (re-find #"usage: bb gherkin-parser <feature-file> <json-output>" (:output result)))
    (is (re-find #"Arguments:" (:output result)))
    (is (re-find #"<feature-file>" (:output result)))
    (is (re-find #"Exit codes:" (:output result)))))
