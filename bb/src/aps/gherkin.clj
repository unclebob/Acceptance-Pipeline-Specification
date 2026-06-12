(ns aps.gherkin
  (:require [aps.json :as aps-json]
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

(defn parse-string [source]
  (loop [lines (map-indexed vector (str/split-lines source))
         feature (array-map :name "" :scenarios [])
         current nil
         section :none
         headers nil]
    (if-let [[line-index raw] (first lines)]
      (let [line-no (inc line-index)
            line (str/trim raw)]
        (cond
          (or (str/blank? line) (str/starts-with? line "#"))
          (recur (rest lines) feature current section headers)

          (str/starts-with? line "Feature:")
          (recur (rest lines)
                 (assoc feature :name (str/trim (subs line (count "Feature:"))))
                 nil :none nil)

          (= line "Background:")
          (recur (rest lines) feature nil :background nil)

          (str/starts-with? line "Scenario Outline:")
          (let [scenario (array-map :name (str/trim (subs line (count "Scenario Outline:")))
                                    :steps []
                                    :examples [])
                idx (count (:scenarios feature))]
            (recur (rest lines)
                   (update feature :scenarios conj scenario)
                   idx :scenario nil))

          (str/starts-with? line "Scenario:")
          (let [scenario (array-map :name (str/trim (subs line (count "Scenario:")))
                                    :steps []
                                    :examples [])
                idx (count (:scenarios feature))]
            (recur (rest lines)
                   (update feature :scenarios conj scenario)
                   idx :scenario nil))

          (= line "Examples:")
          (if (nil? current)
            (throw (ex-info (format "line %d: examples outside scenario" line-no) {}))
            (recur (rest lines) feature current :examples nil))

          (str/starts-with? line "|")
          (if (or (not= section :examples) (nil? current))
            (recur (rest lines) feature current section headers)
            (let [cells (parse-table-row line)]
              (if (nil? headers)
                (recur (rest lines) feature current section cells)
                (do
                  (when (not= (count cells) (count headers))
                    (throw (ex-info (format "line %d: example row has %d cells, header has %d"
                                            line-no (count cells) (count headers)) {})))
                  (recur (rest lines)
                         (update-in feature [:scenarios current :examples]
                                    conj (into (array-map) (map vector headers cells)))
                         current section headers)))))

          (step? line)
          (let [step (parse-step line)]
            (case section
              :background
              (recur (rest lines) (update feature :background (fnil conj []) step) current section headers)

              (:scenario :examples)
              (if (nil? current)
                (throw (ex-info (format "line %d: step outside scenario" line-no) {}))
                (recur (rest lines)
                       (update-in feature [:scenarios current :steps] conj step)
                       current :scenario headers))

              (throw (ex-info (format "line %d: step outside background or scenario" line-no) {}))))

          :else
          (recur (rest lines) feature current section headers)))
      (do
        (when (str/blank? (:name feature))
          (throw (ex-info "missing feature declaration" {})))
        feature))))

(defn parse-file [path]
  (parse-string (slurp path)))

(defn write-json! [path feature]
  (aps-json/write-pretty-file! path (aps-json/strip-empty-keys #{:background :parameters} feature)))
