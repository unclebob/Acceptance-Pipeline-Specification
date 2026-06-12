(ns aps.dry
  (:require [aps.json :as aps-json]
            [clojure.math :as math]
            [clojure.set :as set]
            [clojure.string :as str]))

(def parameter-re #"<([A-Za-z0-9_]+)>")
(def non-token-re #"[^a-z0-9]+")
(def stop-words #{"a" "an" "and" "are" "is" "of" "the" "to" "with" "in" "has" "have"})

(defn- normalize-placeholders [text]
  (let [i (atom 0)]
    (str/replace text parameter-re (fn [_] (str "<_" (swap! i inc) ">")))))

(defn- canonical-from-normalized [normalized]
  (let [i (atom 0)]
    (str/replace normalized #"<_[0-9]+>"
                 (fn [_]
                   (if (= 1 (swap! i inc))
                     "<value>"
                     (str "<value_" @i ">"))))))

(defn- regex-quote [s]
  (str/replace s #"([\\\.\+\*\?\(\)\|\[\]\{\}\^\$])" "\\\\$1"))

(defn- regex-from-normalized [normalized]
  (str "^" (str/replace normalized #"<_[0-9]+>" (fn [_] "(.+)")) "$"))

(defn- exact-pattern [text]
  (str "^" (regex-quote text) "$"))

(defn- tokens [text]
  (->> (str/lower-case (str/replace text parameter-re " "))
       (#(str/replace % non-token-re " "))
       str/trim
       (#(if (str/blank? %) [] (str/split % #"\s+")))
       (remove #(or (<= (count %) 1) (stop-words %)))
       set))

(defn- location [section scenario-index scenario-name step-index keyword]
  (cond-> (array-map :section section)
    (some? scenario-index) (assoc :scenario_index scenario-index)
    (seq scenario-name) (assoc :scenario_name scenario-name)
    true (assoc :step_index step-index :keyword keyword)))

(defn- collect-steps [feature]
  (concat
   (map-indexed
    (fn [i step]
      {:text (:text step) :keyword (:keyword step)
       :location (location "background" nil nil i (:keyword step))
       :normalized (normalize-placeholders (:text step))
       :tokens (tokens (:text step))})
    (:background feature))
   (mapcat
    (fn [[scenario-index scenario]]
      (map-indexed
       (fn [step-index step]
         {:text (:text step) :keyword (:keyword step)
          :location (location "scenario" scenario-index (:name scenario) step-index (:keyword step))
          :normalized (normalize-placeholders (:text step))
          :tokens (tokens (:text step))})
       (:steps scenario)))
    (map-indexed vector (:scenarios feature)))))

(defn- members-by-text [entries]
  (reduce (fn [result entry]
            (update result (:text entry)
                    (fn [member]
                      (-> (or member (array-map :text (:text entry) :locations []))
                          (update :locations conj (:location entry))))))
          (sorted-map)
          entries))

(defn- scenario-duplicate-key [entry]
  (if (= "background" (get-in entry [:location :section]))
    (str "background\u0000" (:text entry))
    (str "scenario\u0000" (get-in entry [:location :scenario_index] -1) "\u0000" (:text entry))))

(defn- duplicate-in-scenario-findings [entries]
  (->> entries
       (reduce (fn [groups entry]
                 (update groups (scenario-duplicate-key entry)
                         (fn [member]
                           (-> (or member (array-map :text (:text entry) :locations []))
                               (update :locations conj (:location entry))))))
               {})
       vals
       (filter #(>= (count (:locations %)) 2))
       (mapv (fn [member]
               (array-map :kind "duplicate-in-scenario"
                          :confidence "high"
                          :canonical_candidate (:text member)
                          :pattern_candidate (exact-pattern (:text member))
                          :members [member]
                          :reason "same step text appears more than once in the same background or scenario"
                          :suggested_action "Review the scenario for an accidental repeated step; keep it only if the repeated execution is intentional.")))))

(defn- exact-duplicate-findings [by-text]
  (->> (vals by-text)
       (filter #(>= (count (:locations %)) 2))
       (mapv (fn [member]
               (array-map :kind "exact-duplicate"
                          :confidence "high"
                          :canonical_candidate (:text member)
                          :pattern_candidate (exact-pattern (:text member))
                          :members [member]
                          :reason "same step text appears more than once in the IR"
                          :suggested_action "Treat this as a vocabulary reuse audit; repeated use across scenarios is usually acceptable.")))))

(defn- members-for-texts [texts by-text]
  (->> texts sort (mapv by-text)))

(defn- placeholder-variant-findings [entries by-text]
  (let [groups (reduce (fn [groups entry]
                         (if (= (:normalized entry) (:text entry))
                           groups
                           (update groups (:normalized entry) (fnil conj #{}) (:text entry))))
                       {}
                       entries)]
    (->> groups
         (filter (fn [[_ texts]] (>= (count texts) 2)))
         (mapv (fn [[normalized texts]]
                 (array-map :kind "placeholder-variant"
                            :confidence "high"
                            :canonical_candidate (canonical-from-normalized normalized)
                            :pattern_candidate (regex-from-normalized normalized)
                            :members (members-for-texts texts by-text)
                            :reason "step text is identical after replacing placeholder names with generic slots"
                            :suggested_action "Review the feature wording and normalize the Gherkin if the different placeholder names do not add meaning."))))))

(defn- jaccard [left right]
  (if (and (empty? left) (empty? right))
    0.0
    (/ (double (count (set/intersection left right)))
       (double (count (set/union left right))))))

(defn- round3 [value]
  (let [rounded (/ (math/round (* value 1000.0)) 1000.0)]
    (if (= rounded (double (long rounded)))
      (long rounded)
      rounded)))

(defn- similarity-findings [by-text]
  (let [texts (vec (sort (keys by-text)))]
    (loop [pairs (for [i (range (count texts))
                      j (range (inc i) (count texts))]
                  [(texts i) (texts j)])
           findings []]
      (if-let [[left right] (first pairs)]
        (let [left-norm (normalize-placeholders left)
              right-norm (normalize-placeholders right)
              score (jaccard (tokens left-norm) (tokens right-norm))]
          (if (or (= left-norm right-norm) (< score 0.45))
            (recur (rest pairs) findings)
            (let [[kind reason] (if (>= score 0.72)
                                  ["near-duplicate" "step texts are highly similar after placeholder normalization"]
                                  ["possible-synonym" "step texts share many non-placeholder tokens and may describe the same concept"])]
              (recur (rest pairs)
                     (conj findings
                           (array-map :kind kind
                                      :confidence "medium"
                                      :members (members-for-texts #{left right} by-text)
                                      :reason reason
                                      :suggested_action "Review manually before editing; normalize the Gherkin only when the different wording is accidental drift."
                                      :score (round3 score)))))))
        findings))))

(defn- kind-rank [kind]
  (case kind
    "duplicate-in-scenario" 0
    "exact-duplicate" 1
    "placeholder-variant" 2
    "near-duplicate" 3
    4))

(defn- finding-sort-text [finding]
  (if (empty? (:members finding))
    (:kind finding)
    (str/join "\u0000" (map :text (:members finding)))))

(defn- sort-findings [findings]
  (->> findings
       (map-indexed vector)
       (sort (fn [[left-index left] [right-index right]]
               (let [left-key [(kind-rank (:kind left))
                               (- (double (or (:score left) 0)))
                               (finding-sort-text left)]
                     right-key [(kind-rank (:kind right))
                                (- (double (or (:score right) 0)))
                                (finding-sort-text right)]
                     compared (compare left-key right-key)]
                 (if (zero? compared)
                   (< left-index right-index)
                   (neg? compared)))))
       (mapv second)))

(defn analyze
  ([feature] (analyze feature {}))
  ([feature {:keys [include-exact]}]
   (let [entries (vec (collect-steps feature))
         by-text (members-by-text entries)
         findings (sort-findings
                   (concat (duplicate-in-scenario-findings entries)
                           (when include-exact (exact-duplicate-findings by-text))
                           (placeholder-variant-findings entries by-text)
                           (similarity-findings by-text)))]
     (array-map :schema_version 1
                :feature_name (:name feature)
                :summary (array-map :step_occurrences (count entries)
                                    :unique_steps (count by-text)
                                    :findings (count findings))
                :findings (vec findings)))))

(defn write-json! [path report]
  (aps-json/write-pretty-file! path (aps-json/strip-nil report)))
