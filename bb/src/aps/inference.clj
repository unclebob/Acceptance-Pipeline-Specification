(ns aps.inference
  (:require [clojure.string :as str]))

(def parameter-re #"<([A-Za-z0-9_]+)>")

(def copula-prefix-re #"(?i)(?:^|\s)(is|equals|has) $")

(defn- parameters [text]
  (mapv second (re-seq parameter-re text)))

(defn- int-literal? [token]
  (and (re-matches #"-?\d+" token)
       (try (Long/parseLong token) true (catch Exception _ false))))

(defn- float-literal? [token]
  (and (str/includes? token ".")
       (try (let [f (Double/parseDouble token)]
              (and (not (Double/isInfinite f)) (not (Double/isNaN f))))
            (catch Exception _ false))))

(defn- bool-literal? [token]
  (#{"true" "false"} (str/lower-case token)))

(defn- null-literal? [token]
  (#{"null" "nil" "none"} (str/lower-case token)))

(defn- iso-date-time-literal? [token]
  (re-matches #"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?" token))

(defn- iso-date-literal? [token]
  (re-matches #"\d{4}-\d{2}-\d{2}" token))

(defn- iso-time-literal? [token]
  (re-matches #"\d{2}:\d{2}:\d{2}(?:\.\d+)?" token))

(defn- comma-list-literal? [text]
  (when (str/includes? text ",")
    (let [parts (mapv str/trim (str/split text #","))]
      (when (and (<= 2 (count parts))
                 (every? #(or (int-literal? %) (float-literal? %)) parts))
        text))))

(defn- typed-token-literal? [token]
  (or (int-literal? token)
      (float-literal? token)
      (bool-literal? token)
      (null-literal? token)
      (iso-date-time-literal? token)
      (iso-date-literal? token)
      (iso-time-literal? token)))

(defn- copula-token-literal? [text pos token]
  (when (re-find copula-prefix-re (subs text 0 pos))
    token))

(defn- first-promotion [text pos]
  (let [sub (subs text pos)]
    (or (when-let [[whole inner] (re-matches #"^\"([^\"]*)\"" sub)]
          {:start pos :end (+ pos (count whole)) :value inner})
        (when-let [[whole] (re-matches #"^(-?\d+(?:\.\d+)?(?:\s*,\s*-?\d+(?:\.\d+)?)+)" sub)]
          {:start pos :end (+ pos (count whole)) :value whole})
        (when-let [[whole] (re-matches #"^(\S+)" sub)]
          (let [token whole]
            (when-let [value (or (and (typed-token-literal? token) token)
                                 (copula-token-literal? text pos token))]
              {:start pos :end (+ pos (count whole)) :value value}))))))

(defn- scan-literal [text offset]
  (loop [pos 0 acc []]
    (if (>= pos (count text))
      acc
      (if-let [{:keys [start end value]} (first-promotion text pos)]
        (recur end (conj acc {:start (+ offset start) :end (+ offset end) :value value}))
        (recur (inc pos) acc)))))

(defn- scan-step-text [text]
  (let [matcher (re-matcher parameter-re text)]
    (loop [last-end 0 acc []]
      (if (.find matcher)
        (let [start (.start matcher)]
          (recur (.end matcher)
                 (into acc (scan-literal (subs text last-end start) last-end))))
        (into acc (scan-literal (subs text last-end) last-end))))))

(defn- next-param-name [n used]
  (loop [i n]
    (let [name (str "p" i)]
      (if (contains? used name)
        (recur (inc i))
        [name (inc i) (conj used name)]))))

(defn- assign-promotion-names [promotions used n]
  (loop [promotions promotions assigned [] bindings [] n n used used]
    (if-let [promotion (first promotions)]
      (let [[name n' used'] (next-param-name n used)]
        (recur (rest promotions)
               (conj assigned (assoc promotion :name name))
               (conj bindings {:name name :value (:value promotion)})
               n'
               used'))
      {:promotions assigned :bindings bindings :next n :used used})))

(defn- rewrite-text [text promotions]
  (reduce (fn [t {:keys [start end name]}]
            (str (subs t 0 start) "<" name ">" (subs t end)))
          text
          (sort-by :start > promotions)))

(defn- infer-step [step used n]
  (let [promotions (scan-step-text (:text step))
        {:keys [promotions bindings next used]}
        (assign-promotion-names promotions used n)
        text (rewrite-text (:text step) promotions)]
    {:step (cond-> (assoc step :text text)
             (seq (parameters text)) (assoc :parameters (parameters text)))
     :bindings bindings
     :next next
     :used used}))

(defn- infer-steps [steps used n]
  (loop [steps steps rewritten [] acc-bindings [] n n used used]
    (if-let [step (first steps)]
      (let [{:keys [step bindings next used]}
            (infer-step step used n)]
        (recur (rest steps)
               (conj rewritten step)
               (into acc-bindings bindings)
               next
               used))
      {:steps rewritten :bindings acc-bindings :next n :used used})))

(defn- example-keys [examples]
  (into #{} (mapcat keys examples)))

(defn- merge-bindings [examples bindings]
  (if (empty? bindings)
    examples
    (let [rows (if (seq examples) examples [{}])
          binding-map (into {} (map (fn [{:keys [name value]}] [name value]) bindings))]
      (mapv #(merge % binding-map) rows))))

(defn- infer-scenario [scenario background-bindings]
  (let [used (into (example-keys (:examples scenario))
                   (map :name background-bindings))
        start-n (inc (count background-bindings))
        {:keys [steps bindings]}
        (infer-steps (:steps scenario) used start-n)
        all-bindings (into background-bindings bindings)]
    (assoc scenario
           :steps steps
           :examples (merge-bindings (:examples scenario) all-bindings))))

(defn- explicit-parameter-names [steps]
  (set (mapcat (comp parameters :text) steps)))

(defn- validate-explicit-parameters! [feature]
  (doseq [scenario (:scenarios feature)
          :let [required (into (explicit-parameter-names (:background feature))
                               (explicit-parameter-names (:steps scenario)))]
          :when (seq required)
          :let [rows (:examples scenario)
                available (if (seq rows) (set (mapcat keys rows)) #{})
                missing (sort (remove available required))]
          :when (seq missing)]
    (throw (ex-info (str "missing example columns for explicit placeholders: "
                          (str/join ", " missing))
                    {:missing missing}))))

(defn apply! [feature]
  (let [{:keys [steps bindings]}
        (infer-steps (:background feature) #{} 1)
        feature' (-> feature
                     (assoc :background steps)
                     (update :scenarios (fn [scenarios]
                                            (mapv #(infer-scenario % bindings) scenarios))))]
    (validate-explicit-parameters! feature')
    feature'))