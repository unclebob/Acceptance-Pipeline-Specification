(ns aps.inference-test
  (:require [aps.gherkin :as gherkin]
            [aps.inference :as inference]
            [clojure.test :refer [deftest is testing]]))

(deftest infers-typed-literal-into-example-column
  (let [feature (gherkin/parse-string "Feature: Demo\n\nScenario: Set value\n  Given A is 1\n"
                                      {:infer? false})
        inferred (inference/apply! feature)]
    (is (= [{:keyword "Given" :text "A is <p1>" :parameters ["p1"]}]
           (get-in inferred [:scenarios 0 :steps])))
    (is (= [{"p1" "1"}] (get-in inferred [:scenarios 0 :examples])))))

(deftest does-not-deduplicate-same-literal-values
  (let [feature (gherkin/parse-string "Feature: Demo\n\nScenario: Values\n  Given A is 1\n  And B is 1\n"
                                      {:infer? false})
        inferred (inference/apply! feature)]
    (is (= [{:keyword "Given" :text "A is <p1>" :parameters ["p1"]}
            {:keyword "And" :text "B is <p2>" :parameters ["p2"]}]
           (get-in inferred [:scenarios 0 :steps])))
    (is (= [{"p1" "1" "p2" "1"}] (get-in inferred [:scenarios 0 :examples])))))

(deftest infers-copula-identifier-and-quoted-string
  (let [feature (gherkin/parse-string "Feature: Demo\n\nScenario: Status\n  Given status is accepted\n  And message is \"hello world\"\n"
                                      {:infer? false})
        inferred (inference/apply! feature)]
    (is (= [{:keyword "Given" :text "status is <p1>" :parameters ["p1"]}
            {:keyword "And" :text "message is <p2>" :parameters ["p2"]}]
           (get-in inferred [:scenarios 0 :steps])))
    (is (= [{"p1" "accepted" "p2" "hello world"}]
           (get-in inferred [:scenarios 0 :examples])))))

(deftest merges-inferred-columns-into-existing-examples
  (let [feature (gherkin/parse-string "Feature: Withdrawals\n\nScenario Outline: Withdraw cash\n  Given balance is 100\n  When the customer withdraws <amount>\n  Then the remaining balance is <remaining>\n\nExamples:\n  | amount | remaining |\n  | 20     | 80        |\n  | 5      | 45        |\n"
                                      {:infer? false})
        inferred (inference/apply! feature)]
    (is (= [{:keyword "Given" :text "balance is <p1>" :parameters ["p1"]}
            {:keyword "When" :text "the customer withdraws <amount>" :parameters ["amount"]}
            {:keyword "Then" :text "the remaining balance is <remaining>" :parameters ["remaining"]}]
           (get-in inferred [:scenarios 0 :steps])))
    (is (= [{"amount" "20" "remaining" "80" "p1" "100"}
            {"amount" "5" "remaining" "45" "p1" "100"}]
           (get-in inferred [:scenarios 0 :examples])))))

(deftest merges-background-inference-into-scenario-examples
  (let [feature (gherkin/parse-string "Feature: Withdrawals\n\nBackground:\n  Given account type is premium\n\nScenario Outline: Withdraw cash\n  When withdraw <amount>\n\nExamples:\n  | amount |\n  | 20     |\n"
                                      {:infer? false})
        inferred (inference/apply! feature)]
    (is (= [{:keyword "Given" :text "account type is <p1>" :parameters ["p1"]}]
           (:background inferred)))
    (is (= [{"amount" "20" "p1" "premium"}]
           (get-in inferred [:scenarios 0 :examples])))))

(deftest synthesizes-example-row-from-background-when-scenario-has-none
  (let [feature (gherkin/parse-string "Feature: Demo\n\nBackground:\n  Given level is 3\n\nScenario: Check\n  Then status is accepted\n"
                                      {:infer? false})
        inferred (inference/apply! feature)]
    (is (= [{"p1" "3" "p2" "accepted"}]
           (get-in inferred [:scenarios 0 :examples])))))

(deftest parse-string-enables-inference-by-default
  (let [feature (gherkin/parse-string "Feature: Demo\n\nScenario: Set value\n  Given A is 1\n")]
    (is (= [{"p1" "1"}] (get-in feature [:scenarios 0 :examples])))))

(deftest parse-string-supports-do-not-infer
  (let [feature (gherkin/parse-string "Feature: Demo\n\nScenario: Set value\n  Given A is 1\n"
                                      {:infer? false})]
    (is (= [{:keyword "Given" :text "A is 1"}]
           (get-in feature [:scenarios 0 :steps])))
    (is (= [] (get-in feature [:scenarios 0 :examples])))))

(deftest rejects-missing-explicit-placeholder-column
  (let [feature (gherkin/parse-string "Feature: Demo\n\nScenario Outline: Withdraw\n  When withdraw <amount>\n\nExamples:\n  | remaining |\n  | 80        |\n"
                                      {:infer? false})]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"missing example columns for explicit placeholders: amount"
                          (inference/apply! feature)))))

(deftest leaves-explicit-placeholders-uninferred
  (let [feature (gherkin/parse-string "Feature: Demo\n\nScenario Outline: Withdraw\n  When withdraw <amount>\n\nExamples:\n  | amount |\n  | 20     |\n"
                                      {:infer? false})
        inferred (inference/apply! feature)]
    (is (= [{:keyword "When" :text "withdraw <amount>" :parameters ["amount"]}]
           (get-in inferred [:scenarios 0 :steps])))
    (is (= [{"amount" "20"}] (get-in inferred [:scenarios 0 :examples])))))