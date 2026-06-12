(ns aps.dry-test
  (:require [aps.dry :as dry]
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
