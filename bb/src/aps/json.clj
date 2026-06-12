(ns aps.json
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]))

(declare strip-nil strip-empty-keys)

(def pretty-json-options {:pretty Boolean/TRUE})

(defn- strip-coll [value keep-entry strip-item]
  (cond
    (map? value)
    (into (array-map) (keep keep-entry) value)

    (vector? value)
    (mapv strip-item value)

    (sequential? value)
    (mapv strip-item value)

    :else value))

(defn- keep-non-nil-entry [[k v]]
  (when-some [v' (strip-nil v)]
    [k v']))

(defn strip-nil [value]
  (strip-coll value keep-non-nil-entry strip-nil))

(defn- omitted-empty-entry? [keys k v]
  (and (keys k) (coll? v) (empty? v)))

(defn- keep-non-empty-entry [keys [k v]]
  (let [v' (strip-empty-keys keys v)]
    (when-not (or (nil? v') (omitted-empty-entry? keys k v'))
      [k v'])))

(defn strip-empty-keys [keys value]
  (strip-coll value #(keep-non-empty-entry keys %) #(strip-empty-keys keys %)))

(defn write-pretty-file! [path value]
  (io/make-parents path)
  (spit path (str (json/generate-string value pretty-json-options) "\n")))

(defn write-pretty-out! [value]
  (println (json/generate-string value pretty-json-options)))

(defn read-json-file [path]
  (json/parse-string (slurp path) true))