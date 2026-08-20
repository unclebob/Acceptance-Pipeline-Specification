(ns aps.gherkin
  (:require [aps.inference :as inference]
            [aps.json :as aps-json]
            [clojure.string :as str]))

(def parameter-re #"<([A-Za-z0-9_]+)>")

(defn- parameters [text]
  (mapv second (re-seq parameter-re text)))

(defn- parse-step [line]
  (let [[keyword text] (str/split line #" " 2)]
    (cond-> (array-map :keyword keyword
                       :text (str/trim (or text "")))
      (seq (parameters (str/trim (or text ""))))
      (assoc :parameters (parameters (str/trim (or text "")))))))

(defn- step? [line]
  (some #(str/starts-with? line %) ["Given " "When " "Then " "And "]))

(defn- parse-table-row [line]
  (->> (-> line str/trim (str/replace #"^\|" "") (str/replace #"\|$" ""))
       (#(str/split % #"\|"))
       (mapv str/trim)))

(def line-classifiers
  [[:skip #(or (str/blank? %) (str/starts-with? % "#"))]
   [:feature #(str/starts-with? % "Feature:")]
   [:background #(= % "Background:")]
   [:scenario-outline #(str/starts-with? % "Scenario Outline:")]
   [:scenario #(str/starts-with? % "Scenario:")]
   [:examples #(= % "Examples:")]
   [:table #(str/starts-with? % "|")]
   [:step step?]])

(defn- classify-line [line]
  (or (some (fn [[kind matches?]] (when (matches? line) kind)) line-classifiers)
      :ignored))

(defn- scenario [prefix line]
  (array-map :name (str/trim (subs line (count prefix)))
             :steps []
             :examples []))

(defn- add-scenario [state scenario]
  (-> state
      (update-in [:feature :scenarios] conj scenario)
      (assoc :current (count (get-in state [:feature :scenarios]))
             :section :scenario
             :headers nil)))

(defn- add-example-row [state cells line-no]
  (let [{:keys [headers current]} state]
    (when (not= (count cells) (count headers))
      (throw (ex-info (format "line %d: example row has %d cells, header has %d"
                              line-no (count cells) (count headers)) {})))
    (update-in state [:feature :scenarios current :examples]
               conj (into (array-map) (map vector headers cells)))))

(defn- current-steps-path [state]
  (case (:section state)
    :background [:feature :background]
    :scenario (when (some? (:current state))
                [:feature :scenarios (:current state) :steps])
    nil))

(defn- reject-table-row [line-no]
  (throw (ex-info (format "line %d: table row is not attached to a step" line-no) {})))

(defn- reject-table-width [line-no cells headers]
  (throw (ex-info (format "line %d: table row has %d cells, header has %d"
                          line-no (count cells) (count headers)) {})))

(defn- start-step-table [state path idx cells]
  (-> state
      (assoc :headers cells)
      (assoc-in (conj path idx :table) (array-map :headers cells :rows []))))

(defn- append-step-table-row [state path idx cells line-no]
  (let [headers (:headers state)]
    (when (not= (count cells) (count headers))
      (reject-table-width line-no cells headers))
    (update-in state (conj path idx :table :rows) conj cells)))

(defn- add-step-table-row [state cells line-no]
  (let [path (current-steps-path state)
        steps (when path (get-in state path))
        idx (when (seq steps) (dec (count steps)))]
    (cond
      (or (nil? path) (nil? idx)) (reject-table-row line-no)
      (nil? (:headers state)) (start-step-table state path idx cells)
      :else (append-step-table-row state path idx cells line-no))))

(defn- apply-example-table-row [state cells line-no]
  (if (nil? (:headers state))
    (assoc state :headers cells)
    (add-example-row state cells line-no)))

(defn- apply-table-line [state line line-no]
  (let [cells (parse-table-row line)]
    (if (= :examples (:section state))
      (apply-example-table-row state cells line-no)
      (add-step-table-row state cells line-no))))

(def step-handlers
  {:background (fn [state step _]
                 (-> state
                     (update-in [:feature :background] (fnil conj []) step)
                     (assoc :headers nil)))
   :scenario (fn [state step _]
               (-> state
                   (update-in [:feature :scenarios (:current state) :steps] conj step)
                   (assoc :section :scenario :headers nil)))
   :examples (fn [state step line-no]
               ((:scenario step-handlers) state step line-no))})

(defn- add-step [state step line-no]
  (if-let [handler (step-handlers (:section state))]
    (if (and (#{:scenario :examples} (:section state)) (nil? (:current state)))
      (throw (ex-info (format "line %d: step outside scenario" line-no) {}))
      (handler state step line-no))
    (throw (ex-info (format "line %d: step outside background or scenario" line-no) {}))))

(def line-handlers
  {:skip (fn [state _ _] state)
   :feature (fn [state line _] (assoc-in state [:feature :name] (str/trim (subs line (count "Feature:")))))
   :background (fn [state _ _] (assoc state :current nil :section :background :headers nil))
   :scenario-outline (fn [state line _] (add-scenario state (scenario "Scenario Outline:" line)))
   :scenario (fn [state line _] (add-scenario state (scenario "Scenario:" line)))
   :examples (fn [state _ line-no]
               (if (nil? (:current state))
                 (throw (ex-info (format "line %d: examples outside scenario" line-no) {}))
                 (assoc state :section :examples :headers nil)))
   :table apply-table-line
   :step (fn [state line line-no] (add-step state (parse-step line) line-no))
   :ignored (fn [state _ _] state)})

(defn- apply-line [state line line-no]
  ((line-handlers (classify-line line)) state line line-no))

(defn- parse-source [source]
  (let [initial {:feature (array-map :name "" :scenarios [])
                 :current nil
                 :section :none
                 :headers nil}
        state (reduce (fn [state [index raw]]
                        (apply-line state (str/trim raw) (inc index)))
                      initial
                      (map-indexed vector (str/split-lines source)))
        feature (:feature state)]
    (when (str/blank? (:name feature))
      (throw (ex-info "missing feature declaration" {})))
    feature))

(defn parse-string
  ([source] (parse-string source {:infer? true}))
  ([source {:keys [infer?] :or {infer? true}}]
   (cond-> (parse-source source)
     infer? inference/apply!)))

(defn parse-file
  ([path] (parse-file path {:infer? true}))
  ([path opts] (parse-string (slurp path) opts)))

(defn write-json! [path feature]
  (aps-json/write-pretty-file! path (aps-json/strip-empty-keys #{:background :parameters} feature)))