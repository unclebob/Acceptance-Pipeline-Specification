(ns aps.gherkin-test
  (:require [aps.gherkin :as gherkin]
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
                          #"example row has 1 cells, header has 2"
                          (gherkin/parse-string "Feature: Bad\nScenario Outline: mismatch\n  Given <x>\nExamples:\n  | x | y |\n  | 1 |\n")))))
