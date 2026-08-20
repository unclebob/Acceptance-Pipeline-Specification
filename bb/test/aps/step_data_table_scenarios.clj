(ns aps.step-data-table-scenarios
  (:require [aps.gherkin :as gherkin]
            [aps.json :as aps-json]
            [aps.mutation :as mutation]
            [aps.scenario-runner :as runner]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(defn- step [pattern f]
  {:pattern (re-pattern (str "^" pattern "$"))
   :f f})

(defn- parsed-feature [world]
  (:feature @world))

(defn- all-steps [feature]
  (concat (:background feature) (mapcat :steps (:scenarios feature))))

(defn- find-named-step [feature text]
  (or (some #(when (= text (:text %)) %) (all-steps feature))
      (throw (ex-info (str "no step with text: " text) {:text text}))))

(defn- find-scenario [feature name]
  (or (some #(when (= name (:name %)) %) (:scenarios feature))
      (throw (ex-info (str "no scenario named: " name) {:name name}))))

(defn- table-cell [feature text row col]
  (get-in (find-named-step feature text) [:table :rows row col]))

(defn- example-rows [scenario headers]
  (mapv (fn [example] (mapv #(get example %) headers)) (:examples scenario)))

(defn- mutation-at [world path]
  (some #(when (= path (:Path %)) %) (:mutations @world)))

(defn- integer-string? [value]
  (boolean (re-matches #"-?\d+" (str value))))

(defn- parse-source [world infer?]
  (try
    (swap! world assoc :feature (gherkin/parse-string (:source @world) {:infer? infer?})
           :error nil)
    (catch Exception e
      (swap! world assoc :feature nil :error e))))

(def steps
  [(step "this feature:"
         (fn [world step]
           (swap! world assoc :source (:doc step))))

   (step "the feature is parsed without inference"
         (fn [world _]
           (parse-source world false)))

   (step "the feature is parsed"
         (fn [world _]
           (parse-source world true)))

   (step "the JSON IR is written and read back"
         (fn [world _]
           (let [file (java.io.File/createTempFile "aps-ir" ".json")]
             (gherkin/write-json! (str file) (parsed-feature world))
             (swap! world assoc :feature (aps-json/read-json-file (str file))))))

   (step "mutations are discovered"
         (fn [world _]
           (swap! world assoc :mutations (mutation/discover (parsed-feature world)))))

   (step "parsing succeeds"
         (fn [world _]
           (is (nil? (:error @world)))
           (is (some? (parsed-feature world)))))

   (step "parsing fails"
         (fn [world _]
           (is (some? (:error @world)))
           (is (nil? (parsed-feature world)))))

   (step "scenario '([^']*)' has no examples"
         (fn [world _ name]
           (is (= [] (:examples (find-scenario (parsed-feature world) name))))))

   (step "scenario '([^']*)' has these examples:"
         (fn [world step name]
           (let [headers (get-in step [:table :headers])
                 expected (get-in step [:table :rows])
                 actual (example-rows (find-scenario (parsed-feature world) name) headers)]
             (is (= expected actual)))))

   (step "the step '([^']*)' has this table:"
         (fn [world step text]
           (is (= (:table step) (:table (find-named-step (parsed-feature world) text))))))

   (step "the step '([^']*)' has no table"
         (fn [world _ text]
           (is (nil? (:table (find-named-step (parsed-feature world) text))))))

   (step "(\\d+) mutations are discovered"
         (fn [world _ n]
           (is (= (Long/parseLong n) (count (:mutations @world))))))

   (step "a mutation exists for path '([^']*)' with original '([^']*)'"
         (fn [world _ path original]
           (let [mutation (mutation-at world path)]
             (is (some? mutation))
             (when mutation
               (is (= original (:Original mutation)))
               (is (not= original (:Mutated mutation)))))))

   (step "no mutation has original '([^']*)'"
         (fn [world _ original]
           (is (not-any? #(= original (:Original %)) (:mutations @world)))))

   (step "no example mutations are discovered"
         (fn [world _]
           (is (not-any? #(str/includes? (:Path %) ".examples[") (:mutations @world)))))

   (step "the mutation at path '([^']*)' is applied"
         (fn [world _ path]
           (let [mutation (mutation-at world path)
                 original (parsed-feature world)]
             (is (some? mutation))
             (when mutation
               (swap! world assoc :original-feature original
                      :selected mutation
                      :feature (mutation/apply-mutation original mutation))))))

   (step "the table cell at row (\\d+) column (\\d+) of '([^']*)' is the mutated value"
         (fn [world _ row col text]
           (is (= (get-in @world [:selected :Mutated])
                  (table-cell (parsed-feature world) text
                              (Long/parseLong row) (Long/parseLong col))))))

   (step "the table cell at row (\\d+) column (\\d+) of '([^']*)' is still '([^']*)'"
         (fn [world _ row col text expected]
           (is (= expected
                  (table-cell (parsed-feature world) text
                              (Long/parseLong row) (Long/parseLong col))))))

   (step "the original table cell at row (\\d+) column (\\d+) of '([^']*)' is still '([^']*)'"
         (fn [world _ row col text expected]
           (is (= expected
                  (table-cell (:original-feature @world) text
                              (Long/parseLong row) (Long/parseLong col))))))

   (step "the mutation at path '([^']*)' has a different integer value"
         (fn [world _ path]
           (let [mutation (mutation-at world path)]
             (is (some? mutation))
             (when mutation
               (is (integer-string? (:Original mutation)))
               (is (integer-string? (:Mutated mutation)))
               (is (not= (:Original mutation) (:Mutated mutation)))))))

   (step "the first mutation path is '([^']*)'"
         (fn [world _ path]
           (is (= path (:Path (first (:mutations @world)))))))

   (step "a later mutation path is '([^']*)'"
         (fn [world _ path]
           (is (some #(= path (:Path %)) (rest (:mutations @world))))))])

(defn- feature-file [name]
  (str (io/file "bb/test/aps/features" name)))

(deftest parser-keeps-step-data-tables
  (runner/run-file (feature-file "step-data-tables.feature") steps))

(deftest mutator-changes-step-data-table-cells
  (runner/run-file (feature-file "step-data-table-mutation.feature") steps))
