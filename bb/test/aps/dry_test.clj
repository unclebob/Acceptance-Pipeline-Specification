(ns aps.dry-test
  (:require [aps.cli-test-helper :as cli-helper]
            [aps.cli.gherkin-ir-dry-checker :as dry-cli]
            [aps.dry :as dry]
            [aps.json :as aps-json]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]))

(def duplication-feature
  {:name "DRY Duplication Examples"
   :background [{:keyword "Given" :text "an account exists for <customer>" :parameters ["customer"]}
                {:keyword "And" :text "an account exists for <customer>" :parameters ["customer"]}]
   :scenarios [{:name "Repeated balance checks"
                :steps [{:keyword "Given" :text "the customer balance is <balance>" :parameters ["balance"]}
                        {:keyword "When" :text "the customer withdraws <amount>" :parameters ["amount"]}
                        {:keyword "Then" :text "the remaining balance is <remaining>" :parameters ["remaining"]}
                        {:keyword "And" :text "the remaining balance is <remaining>" :parameters ["remaining"]}]
                :examples [{"customer" "Ada" "balance" "100" "amount" "20" "remaining" "80"}]}
               {:name "Placeholder naming drift"
                :steps [{:keyword "Given" :text "the user is in room <start_room>" :parameters ["start_room"]}
                        {:keyword "When" :text "the user moves to <destination_room>" :parameters ["destination_room"]}
                        {:keyword "Then" :text "the user is in room <expected_room>" :parameters ["expected_room"]}
                        {:keyword "And" :text "the user is in room <current_room>" :parameters ["current_room"]}]
                :examples [{"start_room" "1" "destination_room" "2" "expected_room" "2" "current_room" "2"}]}
               {:name "Similar account wording"
                :steps [{:keyword "Given" :text "an account exists for Alice"}
                        {:keyword "When" :text "the customer takes 20 dollars"}
                        {:keyword "Then" :text "the balance left is 80"}]
                :examples []}
               {:name "Similar withdrawal wording"
                :steps [{:keyword "Given" :text "an account exists for Alice"}
                        {:keyword "When" :text "the customer withdraws 20 dollars"}
                        {:keyword "Then" :text "the remaining balance is 80"}]
                :examples []}
               {:name "Prompt synonym wording"
                :steps [{:keyword "Then" :text "the output contains prompt Enter command"}
                        {:keyword "And" :text "the output contains line Enter command"}]
                :examples []}]})

(deftest reports-typical-duplications-and-synonyms
  (let [report (dry/analyze duplication-feature {:include-exact true})
        by-kind (frequencies (map :kind (:findings report)))]
    (is (= 1 (:schema_version report)))
    (is (= {:step_occurrences 18
            :unique_steps 15
            :findings 12}
           (:summary report)))
    (is (= {"duplicate-in-scenario" 2
            "exact-duplicate" 3
            "placeholder-variant" 1
            "near-duplicate" 1
            "possible-synonym" 5}
           by-kind))
    (is (= "^an account exists for <customer>$"
           (:pattern_candidate (first (:findings report)))))
    (is (some #(and (= "placeholder-variant" (:kind %))
                    (= "the user is in room <value>" (:canonical_candidate %))
                    (= ["the user is in room <current_room>"
                        "the user is in room <expected_room>"
                        "the user is in room <start_room>"]
                       (mapv :text (:members %))))
              (:findings report)))
    (is (some #(and (= "possible-synonym" (:kind %))
                    (= #{"the customer takes 20 dollars"
                         "the customer withdraws 20 dollars"}
                       (set (map :text (:members %)))))
              (:findings report)))))

(deftest exact-duplicates-are-optional
  (let [report (dry/analyze duplication-feature)]
    (is (nil? (some #{"exact-duplicate"} (map :kind (:findings report)))))
    (is (= 9 (get-in report [:summary :findings])))))

(deftest dry-cli-writes-json-report
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory "aps-dry-cli" (make-array java.nio.file.attribute.FileAttribute 0)))
        input (io/file dir "ir.json")
        output (io/file dir "dry.json")]
    (aps-json/write-pretty-file! input duplication-feature)
    (dry-cli/-main "--include-exact" (str input) (str output))
    (let [report (json/parse-string (slurp output) true)]
    (is (= 12 (get-in report [:summary :findings])))
    (is (= "DRY Duplication Examples" (:feature_name report))))))

(deftest dry-cli-exits-nonzero-on-read-error
  (let [result (cli-helper/run-bb-task ["gherkin-ir-dry-checker" "missing.json" "/tmp/dry.json"])]
    (is (= 1 (:exit result)))
    (is (re-find #"No such file|missing.json" (:output result)))))

(deftest reports-locations-for-background-and-scenario-steps
  (let [report (dry/analyze duplication-feature {:include-exact true})
        locations (mapcat :locations (mapcat :members (:findings report)))]
    (is (every? #(contains? % :keyword) locations))
    (is (every? #(contains? % :step_index) locations))
    (is (some #(= "background" (:section %)) locations))
    (is (some #(= "scenario" (:section %)) locations))))

(deftest detects-two-step-placeholder-variant
  (let [feature {:name "Two placeholders"
                 :scenarios [{:name "Only two"
                              :steps [{:keyword "Then" :text "value is <actual>"}
                                      {:keyword "And" :text "value is <expected>"}]
                              :examples []}]}
        report (dry/analyze feature)]
    (is (some #(and (= "placeholder-variant" (:kind %))
                    (= "value is <value>" (:canonical_candidate %)))
              (:findings report)))))

(deftest dry-score-boundaries-are-inclusive
  (is (= ["possible-synonym" "step texts share many non-placeholder tokens and may describe the same concept"]
         (#'dry/similarity-kind 0.45)))
  (is (= ["near-duplicate" "step texts are highly similar after placeholder normalization"]
         (#'dry/similarity-kind 0.72)))
  (is (nil? (#'dry/similarity-kind 0.449)))
  (is (= 1 (#'dry/round3 1.0)))
  (is (= 0.333 (#'dry/round3 0.3333))))

(deftest tokenization-drops-single-character-words
  (is (= #{"go"} (#'dry/tokens "x go y"))))

(deftest location-always-includes-step-index-and-keyword
  (is (= {:section "background" :step_index 0 :keyword "Given"}
         (#'dry/location "background" nil nil 0 "Given"))))

(deftest finding-sort-text-falls-back-to-kind-for-empty-members
  (is (= "empty-kind" (#'dry/finding-sort-text {:kind "empty-kind" :members []}))))

(deftest sort-findings-preserves-input-order-for-identical-sort-keys
  (let [left {:kind "near-duplicate"
              :score 0.5
              :members [{:text "same"}]
              :marker :left}
        right (assoc left :marker :right)]
    (is (= [:left :right]
           (mapv :marker (#'dry/sort-findings [left right]))))))

(deftest sort-findings-orders-higher-scores-first
  (let [low {:kind "near-duplicate" :score 0.5 :members [{:text "same"}] :marker :low}
        high (assoc low :score 0.75 :marker :high)]
    (is (= [:high :low]
           (mapv :marker (#'dry/sort-findings [low high]))))))

(deftest sort-findings-treats-missing-score-as-zero
  (let [missing {:kind "near-duplicate" :members [{:text "same"}] :marker :missing}
        scored (assoc missing :score 0.5 :marker :scored)]
    (is (= [:scored :missing]
           (mapv :marker (#'dry/sort-findings [missing scored]))))))
