(ns aps.json
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]))

(defn strip-nil [value]
  (cond
    (map? value)
    (into (array-map)
          (keep (fn [[k v]]
                  (let [v' (strip-nil v)]
                    (when-not (nil? v')
                      [k v']))))
          value)

    (vector? value)
    (mapv strip-nil value)

    (sequential? value)
    (mapv strip-nil value)

    :else value))

(defn strip-empty-keys [keys value]
  (cond
    (map? value)
    (into (array-map)
          (keep (fn [[k v]]
                  (let [v' (strip-empty-keys keys v)]
                    (when-not (or (nil? v') (and (keys k) (coll? v') (empty? v')))
                      [k v']))))
          value)

    (vector? value)
    (mapv #(strip-empty-keys keys %) value)

    (sequential? value)
    (mapv #(strip-empty-keys keys %) value)

    :else value))

(defn write-pretty-file! [path value]
  (io/make-parents path)
  (spit path (str (json/generate-string value {:pretty true}) "\n")))

(defn write-pretty-out! [value]
  (println (json/generate-string value {:pretty true})))

(defn read-json-file [path]
  (json/parse-string (slurp path) true))
