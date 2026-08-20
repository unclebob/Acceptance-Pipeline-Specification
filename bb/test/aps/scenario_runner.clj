(ns aps.scenario-runner
  "Glue runner that turns APS's own Given/When/Then feature files into clojure.test checks."
  (:require [clojure.string :as str]
            [clojure.test :refer [testing]]))

(defn- parse-table-row [line]
  (->> (-> line str/trim (str/replace #"^\|" "") (str/replace #"\|$" ""))
       (#(str/split % #"\|"))
       (mapv str/trim)))

(defn- step-line? [line]
  (some #(str/starts-with? line %) ["Given " "When " "Then " "And "]))

(defn- parse-step-line [line]
  (let [[keyword text] (str/split line #" " 2)]
    {:keyword keyword :text (str/trim (or text ""))}))

(defn- last-step-path [state]
  (let [scenarios (get-in state [:feature :scenarios])
        scenario-index (dec (count scenarios))
        step-index (dec (count (get-in scenarios [scenario-index :steps] [])))]
    (when (and (>= scenario-index 0) (>= step-index 0))
      [:feature :scenarios scenario-index :steps step-index])))

(defn- require-last-step [state action]
  (or (last-step-path state)
      (throw (ex-info (str action " without a preceding step") {}))))

(defn- close-doc [state]
  (-> state
      (assoc-in (conj (require-last-step state "doc string") :doc)
                (str/join "\n" (:doc-lines state)))
      (assoc :in-doc? false :doc-lines [])))

(defn- add-scenario [state name]
  (-> state
      (update-in [:feature :scenarios] conj {:name name :steps []})
      (assoc :headers nil)))

(defn- add-step [state line]
  (-> state
      (update-in [:feature :scenarios (dec (count (get-in state [:feature :scenarios]))) :steps]
                 conj (parse-step-line line))
      (assoc :headers nil)))

(defn- add-table-row [state line]
  (let [path (require-last-step state "table row")
        cells (parse-table-row line)]
    (if (nil? (:headers state))
      (-> state
          (assoc :headers cells)
          (assoc-in (conj path :table) {:headers cells :rows []}))
      (update-in state (conj path :table :rows) conj cells))))

(defn- apply-line [state line]
  (let [trimmed (str/trim line)]
    (cond
      (:in-doc? state)
      (if (= trimmed "\"\"\"")
        (close-doc state)
        (update state :doc-lines conj line))

      (= trimmed "\"\"\"")
      (assoc state :in-doc? true :doc-lines [])

      (or (str/blank? trimmed) (str/starts-with? trimmed "#"))
      state

      (str/starts-with? trimmed "Feature:")
      (assoc-in state [:feature :name] (str/trim (subs trimmed (count "Feature:"))))

      (str/starts-with? trimmed "Scenario:")
      (add-scenario state (str/trim (subs trimmed (count "Scenario:"))))

      (step-line? trimmed)
      (add-step state trimmed)

      (str/starts-with? trimmed "|")
      (add-table-row state trimmed)

      :else state)))

(defn parse-scenarios [source]
  (:feature (reduce apply-line
                    {:feature {:name "" :scenarios []}
                     :in-doc? false
                     :headers nil
                     :doc-lines []}
                    (str/split-lines source))))

(defn- match-groups [match]
  (rest (if (vector? match) match [match])))

(defn- find-step-def [defs text]
  (or (some (fn [{:keys [pattern f]}]
              (when-let [match (re-matches pattern text)]
                [f (match-groups match)]))
            defs)
      (throw (ex-info (str "undefined step: " text) {:text text}))))

(defn- run-scenario [scenario defs]
  (let [world (atom {})]
    (doseq [step (:steps scenario)]
      (let [[f args] (find-step-def defs (:text step))]
        (apply f world step args)))))

(defn run-feature [source defs]
  (let [feature (parse-scenarios source)]
    (testing (:name feature)
      (doseq [scenario (:scenarios feature)]
        (testing (:name scenario)
          (run-scenario scenario defs))))))

(defn run-file [path defs]
  (run-feature (slurp path) defs))
