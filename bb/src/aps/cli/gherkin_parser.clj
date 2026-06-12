(ns aps.cli.gherkin-parser
  (:require [aps.gherkin :as gherkin])
  (:gen-class))

(defn -main [& args]
  (if (not= 2 (count args))
    (do
      (binding [*out* *err*]
        (println "usage: gherkin-parser <feature-file> <json-output>"))
      (System/exit 2))
    (try
      (gherkin/write-json! (second args) (gherkin/parse-file (first args)))
      (catch Exception e
        (binding [*out* *err*]
          (println (.getMessage e)))
        (System/exit 1)))))
