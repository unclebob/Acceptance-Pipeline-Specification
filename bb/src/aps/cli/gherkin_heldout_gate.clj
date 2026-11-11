(ns aps.cli.gherkin-heldout-gate
  (:require [aps.heldout :as heldout]
            [aps.json :as aps-json]
            [clojure.string :as str])
  (:gen-class))

(defn -main [& args]
  (when (or (< (count args) 2) (> (count args) 3))
    (binding [*out* *err*]
      (println "usage: gherkin-heldout-gate <visible-results> <heldout-results> [max-gap]"))
    (System/exit 2))
  (try
    (let [[visible-path heldout-path max-gap] args
          gap-limit (if (str/blank? max-gap)
                      heldout/default-max-gap
                      (Double/parseDouble max-gap))
          report (heldout/gate-report
                  (aps-json/read-json-file visible-path)
                  (aps-json/read-json-file heldout-path)
                  gap-limit)]
      (aps-json/write-pretty-out! report)
      (System/exit (if (= "pass" (:status report)) 0 1)))
    (catch Exception e
      (binding [*out* *err*]
        (println (.getMessage e)))
      (System/exit 1))))
