(ns aps.cli.gherkin-heldout-verify
  (:require [aps.heldout :as heldout]
            [aps.json :as aps-json])
  (:gen-class))

(defn -main [& args]
  (when (not= 2 (count args))
    (binding [*out* *err*]
      (println "usage: gherkin-heldout-verify <manifest> <heldout-ir>"))
    (System/exit 2))
  (try
    (let [report (heldout/verify-manifest
                  (aps-json/read-json-file (first args))
                  (aps-json/read-json-file (second args)))]
      (aps-json/write-pretty-out! report)
      (System/exit (if (= "verified" (:status report)) 0 1)))
    (catch Exception e
      (binding [*out* *err*]
        (println (.getMessage e)))
      (System/exit 1))))
